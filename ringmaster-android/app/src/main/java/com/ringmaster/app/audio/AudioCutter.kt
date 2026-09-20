package com.ringmaster.app.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.min

/**
 * Spike③：音频剪切与 AAC/m4a 导出（纯系统 API，零外部依赖）。
 *
 * 管线：MediaExtractor 解复用 → MediaCodec 解码为 PCM → 内存中裁剪 + 线性淡入淡出
 *       → MediaCodec 编码 AAC → MediaMuxer 封装 m4a。
 *
 * 验证点：
 * 1. SAF content:// URI 直读（mp3/m4a/wav/flac 由系统解码器支持）
 * 2. 任意起止点剪切 + 淡入淡出后重新编码为 m4a（Android 设铃声的标准格式）
 * 3. 硬件编解码耗时（目标：3 分钟歌剪切 < 3 秒）
 *
 * 若此路线跑通，Android 端无需引入 ffmpeg（省 ~20MB 包体与 NDK 编译维护）。
 */
class AudioCutter(private val context: Context) {

    data class CutResult(
        val outputFile: File,
        val durationMs: Long,
        val sampleRate: Int,
        val channels: Int,
        val decodeMs: Long = 0,
        val encodeMs: Long = 0
    )

    /** 剪切 [startMs, endMs) 区间并导出 m4a 到 [dest]（扩展名需为 .m4a）。 */
    suspend fun cut(
        inputUri: Uri,
        startMs: Long,
        endMs: Long,
        fadeInMs: Long = 500,
        fadeOutMs: Long = 500,
        dest: File
    ): Result<CutResult> = withContext(Dispatchers.Default) {
        runCatching {
            require(endMs > startMs) { "endMs must > startMs" }
            val startUs = startMs * 1000
            val endUs = endUs(endMs)

            // ---------- 阶段 1：解码选中区间为 PCM ----------
            val t0 = System.currentTimeMillis()
            val amr = AmrDecoder(context)
            val amrInfo = amr.sniff(inputUri)
            val pcm = if (amrInfo != null) {
                // 裸 AMR（通话录音/微信语音）：自写解析管线
                amr.decode(inputUri, amrInfo, startUs, endUs).let {
                    check(it.first.isNotEmpty()) { "decoded no PCM data" }
                    Triple(it.first, it.second, it.third)
                }
            } else {
                val aiffInfo = AiffDecoder(context).sniff(inputUri)
                if (aiffInfo != null) {
                    // AIFF（Mac 无损）：自写解析
                    val aiffPcm = AiffDecoder(context).decode(inputUri, aiffInfo, startUs, endUs)
                    check(aiffPcm.isNotEmpty()) { "decoded no PCM data" }
                    Triple(aiffPcm, aiffInfo.sampleRate, aiffInfo.channels)
                } else {
                    runCatching {
                    val extractor = MediaExtractor()
                    var decoder: MediaCodec? = null
                    try {
                        extractor.setDataSource(context, inputUri, null)
                        val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                            extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                        } ?: error("no audio track")
                        extractor.selectTrack(trackIndex)

                        val srcFormat = extractor.getTrackFormat(trackIndex)
                        val mime = srcFormat.getString(MediaFormat.KEY_MIME)!!
                        val sampleRate = srcFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val channels = srcFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

                        decoder = MediaCodec.createDecoderByType(mime)
                        // FLAC 等高位深格式统一请求 16bit PCM 输出，保证后续字节级处理正确
                        srcFormat.setInteger(
                            MediaFormat.KEY_PCM_ENCODING,
                            android.media.AudioFormat.ENCODING_PCM_16BIT
                        )
                        decoder.configure(srcFormat, null, null, 0)
                        decoder.start()

                        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                        decodeRange(decoder, extractor, startUs, endUs, sampleRate, channels).let {
                            check(it.isNotEmpty()) { "decoded no PCM data" }
                            Triple(it, sampleRate, channels)
                        }
                    } finally {
                        runCatching { decoder?.stop() }
                        runCatching { decoder?.release() }
                        runCatching { extractor.release() }
                    }
                }.getOrThrow()
                }
            }

            val (pcmBytes, sampleRate, channels) = pcm
            val decodeMs = System.currentTimeMillis() - t0
            // FLAC 等格式整形到 AAC 编码器支持的范围（>2 声道取前 2；>48kHz 线性插值降到 44.1kHz）
            val conformed = conformForAac(pcmBytes, sampleRate, channels)
            applyFadeInOut(conformed.first, fadeInMs, fadeOutMs, conformed.second, conformed.third)

            // ---------- 阶段 2：PCM 编码 AAC 并封装 m4a ----------
            val t1 = System.currentTimeMillis()
            dest.parentFile?.mkdirs()
            encodeToM4a(conformed.first, conformed.second, conformed.third, dest)
            val encodeMs = System.currentTimeMillis() - t1
            val samples = conformed.first.size / 2 / conformed.third
            CutResult(
                dest,
                samples * 1000L / conformed.second,
                conformed.second,
                conformed.third,
                decodeMs,
                encodeMs
            )
        }
    }

    /** 解码 [fromUs, untilUs) 的音频（时间轴为源文件绝对时间）。 */
    private fun decodeRange(
        decoder: MediaCodec,
        extractor: MediaExtractor,
        fromUs: Long,
        untilUs: Long,
        sampleRate: Int,
        channels: Int
    ): ByteArray {
        // 按区间长度预分配容量，避免扩容全量拷贝
        val expectedBytes = (((untilUs - fromUs) * sampleRate / 1_000_000L).toInt()) * channels * 2
        val out = java.io.ByteArrayOutputStream(expectedBytes.coerceIn(1 shl 20, 96 shl 20))
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        val maxOutputUs = untilUs - fromUs
        // 非阻塞轮询 + 让步：10ms 阻塞等待 × 万级帧数的累积是性能杀手
        var starveCount = 0
        val starveLimit = 5_000 // 连续无进展上限（约等于数秒空转），防编解码器卡死

        while (!outputDone) {
            var progressed = false
            if (!inputDone) {
                val inIdx = decoder.dequeueInputBuffer(0)
                if (inIdx >= 0) {
                    progressed = true
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
            when (val outIdx = decoder.dequeueOutputBuffer(info, 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> progressed = true
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> progressed = true
                else -> {
                    if (outIdx >= 0) {
                        progressed = true
                        if (info.size > 0) {
                            val buf = decoder.getOutputBuffer(outIdx)!!
                            // 解码输出 pts 从 seek 点重新计（部分实现为绝对时间，两者都按相对裁剪窗口截断）
                            val pts = if (info.presentationTimeUs >= fromUs) info.presentationTimeUs - fromUs
                                      else info.presentationTimeUs
                            if (pts < maxOutputUs) {
                                buf.position(info.offset)
                                buf.limit(info.offset + info.size)
                                val chunk = ByteArray(info.size)
                                buf.get(chunk)
                                out.write(chunk)
                            } else {
                                outputDone = true // 越过终点，提前结束（配合 EOS flag 也行，这里直接断）
                            }
                        }
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        decoder.releaseOutputBuffer(outIdx, false)
                        if (eos) outputDone = true
                    }
                }
            }
            if (!progressed) {
                check(++starveCount < starveLimit) { "decoder stalled" }
                Thread.yield()
            } else {
                starveCount = 0
            }
        }
        return out.toByteArray()
    }

    /** 对 16bit LE 交错 PCM 原地做线性淡入淡出。 */
    private fun applyFadeInOut(pcm: ByteArray, fadeInMs: Long, fadeOutMs: Long, sampleRate: Int, channels: Int) {
        val totalFrames = pcm.size / 2 / channels
        val fadeFrames = intArrayOf(
            (fadeInMs.coerceAtLeast(0) * sampleRate / 1000).toInt().coerceAtMost(totalFrames),
            (fadeOutMs.coerceAtLeast(0) * sampleRate / 1000).toInt().coerceAtMost(totalFrames)
        )
        fun scaleAt(frame: Int): Float {
            val fi = fadeFrames[0]
            val fo = fadeFrames[1]
            val inGain = if (fi <= 0 || frame >= fi) 1f else frame.toFloat() / fi
            val outPos = (totalFrames - 1 - frame).toFloat()
            val outGain = if (fo <= 0 || outPos >= fo) 1f else outPos / fo
            return min(inGain, outGain)
        }
        var frame = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val g = scaleAt(frame)
            if (g < 1f) {
                val s = ((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)).toShort()
                val v = (s * g).toInt().toShort()
                pcm[i] = (v.toInt() and 0xFF).toByte()
                pcm[i + 1] = ((v.toInt() shr 8) and 0xFF).toByte()
            }
            i += 2 * channels
            frame++
        }
    }

    /** PCM(16bit) → AAC(LC, 128kbps) → m4a。 */
    private fun encodeToM4a(pcm: ByteArray, sampleRate: Int, channels: Int, dest: File) {
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(dest.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)
            }
            encoder.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            var muxerStarted = false
            var trackIndex = -1
            var samplesFed = 0L
            val bytesPerFrame = 2 * channels
            val info = MediaCodec.BufferInfo()
            var inputPos = 0
            var inputDone = false
            var outputDone = false
            var starveCount = 0
            val starveLimit = 5_000

            while (!outputDone) {
                var progressed = false
                if (!inputDone) {
                    val inIdx = encoder.dequeueInputBuffer(0)
                    if (inIdx >= 0) {
                        progressed = true
                        val buf = encoder.getInputBuffer(inIdx)!!
                        buf.clear()
                        val chunk = min(buf.remaining(), pcm.size - inputPos)
                        if (chunk > 0) {
                            buf.put(pcm, inputPos, chunk)
                            inputPos += chunk
                        }
                        val ptsUs = samplesFed * 1_000_000L / sampleRate
                        samplesFed += chunk / bytesPerFrame
                        val eos = inputPos >= pcm.size && chunk <= 0
                        encoder.queueInputBuffer(inIdx, 0, maxOf(chunk, 0), ptsUs,
                            if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        if (eos) inputDone = true
                    }
                }
                when (val outIdx = encoder.dequeueOutputBuffer(info, 0)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        progressed = true
                        trackIndex = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    else -> if (outIdx >= 0) {
                        progressed = true
                        val buf = encoder.getOutputBuffer(outIdx)!!
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        if (info.size > 0 && muxerStarted) {
                            muxer.writeSampleData(trackIndex, buf, info)
                        }
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        encoder.releaseOutputBuffer(outIdx, false)
                        if (eos) outputDone = true
                    }
                }
                if (!progressed) {
                    check(++starveCount < starveLimit) { "encoder stalled" }
                    Thread.yield()
                } else {
                    starveCount = 0
                }
            }
            muxer.stop()
        } finally {
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            runCatching { muxer.release() }
        }
    }

    /** PCM 整形到 AAC 编码器支持范围：>2 声道取前 2 声道；>48kHz 线性插值降到 44.1kHz。 */
    private fun conformForAac(pcm: ByteArray, sampleRate: Int, channels: Int): Triple<ByteArray, Int, Int> {
        var bytes = pcm
        var sr = sampleRate
        var ch = channels

        if (ch > 2) {
            val out = ByteArray(bytes.size / ch * 2)
            var i = 0
            var o = 0
            while (i + ch * 2 <= bytes.size) {
                out[o++] = bytes[i]; out[o++] = bytes[i + 1]      // 左声道 LE
                out[o++] = bytes[i + 2]; out[o++] = bytes[i + 3]  // 右声道 LE
                i += ch * 2
            }
            bytes = out; ch = 2
        }

        if (sr > 48000) {
            val targetSr = 44100
            val frames = bytes.size / 2 / ch
            val outFrames = (frames.toLong() * targetSr / sr).toInt()
            val out = ByteArray(outFrames * 2 * ch)
            for (f in 0 until outFrames) {
                val srcPos = f.toDouble() * sr / targetSr
                val i0 = srcPos.toInt()
                val frac = srcPos - i0
                for (c in 0 until ch) {
                    val s0 = readShortLE(bytes, (i0 * ch + c) * 2)
                    val s1 = if (i0 + 1 < frames) readShortLE(bytes, ((i0 + 1) * ch + c) * 2) else s0
                    writeShortLE(out, (f * ch + c) * 2, (s0 + (s1 - s0) * frac).toInt().toShort())
                }
            }
            bytes = out; sr = targetSr
        }
        return Triple(bytes, sr, ch)
    }

    private fun readShortLE(b: ByteArray, off: Int): Short =
        ((b[off].toInt() and 0xFF) or (b[off + 1].toInt() shl 8)).toShort()

    private fun writeShortLE(b: ByteArray, off: Int, v: Short) {
        b[off] = (v.toInt() and 0xFF).toByte()
        b[off + 1] = ((v.toInt() shr 8) and 0xFF).toByte()
    }

    private fun endUs(endMs: Long) = endMs * 1000
}
