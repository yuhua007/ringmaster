package com.ringmaster.app.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Spike④ 数据层：解码整曲 PCM 并降采样为振幅包络（波形数据）。
 *
 * 验证点：
 * 1. content:// URI 全曲解码耗时（目标：5 分钟歌 < 5 秒）
 * 2. 输出桶数按屏幕像素宽度级别（~2000 桶），绘制无压力
 */
class WaveformExtractor(private val context: Context) {

    data class Waveform(val amplitudes: FloatArray, val durationMs: Long)

    suspend fun extract(inputUri: Uri, buckets: Int = 4000): Result<Waveform> =
        withContext(Dispatchers.Default) {
            runCatching {
                // 裸 AMR（通话录音/微信语音）：MediaExtractor 不认，走自写解析管线
                val amr = AmrDecoder(context)
                val amrInfo = amr.sniff(inputUri)
                if (amrInfo != null) {
                    val (pcm, sr, ch) = amr.decode(inputUri, amrInfo, 0, Long.MAX_VALUE / 2)
                    val durMs = pcm.size / 2 / ch * 1000L / sr
                    return@runCatching Waveform(computePeaksFromPcm(pcm, ch, buckets), durMs)
                }
                // AIFF（Mac 无损）：系统同样不认，自写解析
                val aiff = AiffDecoder(context)
                val aiffInfo = aiff.sniff(inputUri)
                if (aiffInfo != null) {
                    val pcm = aiff.decode(inputUri, aiffInfo, 0, Long.MAX_VALUE / 2)
                    val durMs = pcm.size / 2 / aiffInfo.channels * 1000L / aiffInfo.sampleRate
                    return@runCatching Waveform(computePeaksFromPcm(pcm, aiffInfo.channels, buckets), durMs)
                }

                val extractor = MediaExtractor()
                var decoder: MediaCodec? = null
                try {
                    extractor.setDataSource(context, inputUri, null)
                    val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                        extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                    } ?: error("no audio track")
                    extractor.selectTrack(trackIndex)

                    val format = extractor.getTrackFormat(trackIndex)
                    val mime = format.getString(MediaFormat.KEY_MIME)!!
                    val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION))
                        format.getLong(MediaFormat.KEY_DURATION) else 0L

                    decoder = MediaCodec.createDecoderByType(mime).apply {
                        // FLAC 等高位深格式统一请求 16bit PCM 输出
                        format.setInteger(
                            MediaFormat.KEY_PCM_ENCODING,
                            android.media.AudioFormat.ENCODING_PCM_16BIT
                        )
                        configure(format, null, null, 0)
                        start()
                    }

                    // 估算每桶覆盖的采样帧数，边解码边聚合最大振幅
                    val totalSamples = (durationUs * sampleRate / 1_000_000).toInt().coerceAtLeast(1)
                    val samplesPerBucket = (totalSamples / buckets).coerceAtLeast(1)
                    val peaks = FloatArray(buckets)
                    var bucketIdx = 0
                    var samplesInBucket = 0
                    var currentPeak = 0f

                    val info = MediaCodec.BufferInfo()
                    var inputDone = false
                    var outputDone = false
                    var lastPtsUs = 0L // 部分 FLAC 无 DURATION 元数据，用解码 pts 估算时长
                    // 性能优化：批量数组读取（替代逐个 Buffer.get，JIT 可向量化）
                    // + 隔帧采样（frameStride=2：每 2 帧评估一次峰值，视觉无差异，处理量减半）
                    val frameStride = 2
                    var shortArr = ShortArray(0)
                    while (!outputDone) {
                        if (!inputDone) {
                            val inIdx = decoder.dequeueInputBuffer(10_000)
                            if (inIdx >= 0) {
                                val buf = decoder.getInputBuffer(inIdx)!!
                                val size = extractor.readSampleData(buf, 0)
                                if (size < 0) {
                                    decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputDone = true
                                } else {
                                    decoder.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                                    extractor.advance()
                                }
                            }
                        }
                        when (val outIdx = decoder.dequeueOutputBuffer(info, 10_000)) {
                            MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                            else -> if (outIdx >= 0) {
                                if (info.size > 0) {
                                    if (info.presentationTimeUs > lastPtsUs) lastPtsUs = info.presentationTimeUs
                                    val buf = decoder.getOutputBuffer(outIdx)!!
                                    val n = info.size / 2
                                    if (shortArr.size < n) shortArr = ShortArray(n)
                                    buf.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shortArr, 0, n)
                                    var i = 0
                                    while (i + channels <= n) {
                                        var framePeak = 0
                                        for (c in 0 until channels) {
                                            val s = shortArr[i + c]
                                            val v = if (s < 0) -s.toInt() else s.toInt()
                                            if (v > framePeak) framePeak = v
                                        }
                                        val norm = framePeak / 32768f
                                        if (norm > currentPeak) currentPeak = norm
                                        samplesInBucket += frameStride
                                        if (samplesInBucket >= samplesPerBucket) {
                                            if (bucketIdx < peaks.size) peaks[bucketIdx] = currentPeak
                                            bucketIdx++
                                            samplesInBucket = 0
                                            currentPeak = 0f
                                        }
                                        i += channels * frameStride
                                    }
                                }
                                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                decoder.releaseOutputBuffer(outIdx, false)
                                if (eos) outputDone = true
                            }
                        }
                    }
                    if (bucketIdx < buckets) peaks[bucketIdx] = currentPeak
                    val durMs = if (durationUs > 0) durationUs / 1000 else lastPtsUs / 1000 + 200
                    Waveform(peaks, durMs)
                } finally {
                    runCatching { decoder?.stop() }
                    runCatching { decoder?.release() }
                    runCatching { extractor.release() }
                }
            }
        }

    /** 从 16bit LE PCM 直接计算振幅包络（AMR 路径用）。 */
    private fun computePeaksFromPcm(pcm: ByteArray, channels: Int, buckets: Int): FloatArray {
        val peaks = FloatArray(buckets)
        val n = pcm.size / 2
        val frames = n / channels
        val samplesPerBucket = maxOf(1, frames / buckets)
        val frameStride = 2
        var bucketIdx = 0
        var count = 0
        var peak = 0f
        var i = 0
        while (i + channels <= n && bucketIdx < buckets) {
            var framePeak = 0
            for (c in 0 until channels) {
                val s = ((pcm[(i + c) * 2].toInt() and 0xFF) or
                    (pcm[(i + c) * 2 + 1].toInt() shl 8)).toShort()
                val v = if (s < 0) -s.toInt() else s.toInt()
                if (v > framePeak) framePeak = v
            }
            val norm = framePeak / 32768f
            if (norm > peak) peak = norm
            count += frameStride
            if (count >= samplesPerBucket) {
                peaks[bucketIdx++] = peak
                count = 0; peak = 0f
            }
            i += channels * frameStride
        }
        return peaks
    }
}
