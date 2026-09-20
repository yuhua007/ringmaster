package com.ringmaster.app.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.Uri
import java.io.ByteArrayOutputStream

/**
 * 裸 AMR 文件解码（.amr：通话录音/微信语音等常见）。
 *
 * MediaExtractor 不认无容器的裸 AMR，此处自行解析帧结构后喂系统 AMR 解码器：
 * 文件头 "#!AMR\n"(NB 8kHz) / "#!AMR-WB\n"(WB 16kHz) + 帧序列（1 字节 TOC + payload），
 * 每帧 20ms，帧大小由 TOC 的 FT(mode) 查表得到。
 */
class AmrDecoder(private val context: Context) {

    companion object {
        // 各 mode(FT) 的整帧大小（含 TOC 字节），单位字节；0 = 非法，1 = NO_DATA
        private val NB_SIZES = intArrayOf(13, 14, 16, 18, 20, 21, 27, 32, 6, 0, 0, 0, 0, 0, 1, 1)
        private val WB_SIZES = intArrayOf(18, 24, 33, 37, 41, 47, 51, 59, 61, 6, 0, 0, 0, 0, 1, 1)
        private const val FRAME_US = 20_000L
        private val MAGIC_NB = byteArrayOf(0x23, 0x21, 0x41, 0x4D, 0x52, 0x0A)       // "#!AMR\n"
        private val MAGIC_WB = byteArrayOf(0x23, 0x21, 0x41, 0x4D, 0x52, 0x2D, 0x57, 0x42, 0x0A) // "#!AMR-WB\n"
    }

    data class AmrInfo(val isWb: Boolean, val sampleRate: Int, val mime: String)

    /** 嗅探文件头；非 AMR 返回 null。 */
    fun sniff(uri: Uri): AmrInfo? = runCatching {
        val head = ByteArray(9)
        val n = context.contentResolver.openInputStream(uri)?.use { it.read(head) } ?: return null
        val wb = n >= 9 && MAGIC_WB.indices.all { head[it] == MAGIC_WB[it] }
        val nb = n >= 6 && MAGIC_NB.indices.all { head[it] == MAGIC_NB[it] }
        when {
            wb -> AmrInfo(true, 16000, MediaFormat.MIMETYPE_AUDIO_AMR_WB)
            nb -> AmrInfo(false, 8000, MediaFormat.MIMETYPE_AUDIO_AMR_NB)
            else -> null
        }
    }.getOrNull()

    /** 快速扫描帧数估算时长（毫秒）。 */
    fun durationMs(uri: Uri, info: AmrInfo): Long {
        val bytes = readAll(uri)
        val sizes = if (info.isWb) WB_SIZES else NB_SIZES
        val magicLen = if (info.isWb) 9 else 6
        var pos = magicLen
        var frames = 0L
        while (pos < bytes.size) {
            val ft = (bytes[pos].toInt() shr 3) and 0x0F
            val size = if (ft in sizes.indices) sizes[ft] else 0
            if (size <= 0) break
            pos += size
            frames++
        }
        return frames * 20
    }

    /**
     * 解码 [fromUs, untilUs) 区间为 16bit 单声道 PCM。
     * 返回 (pcm, sampleRate, 1)。
     */
    fun decode(uri: Uri, info: AmrInfo, fromUs: Long, untilUs: Long): Triple<ByteArray, Int, Int> {
        val bytes = readAll(uri)
        val sizes = if (info.isWb) WB_SIZES else NB_SIZES
        val magicLen = if (info.isWb) 9 else 6

        val decoder = MediaCodec.createDecoderByType(info.mime)
        val out = ByteArrayOutputStream(1 shl 20)
        try {
            val fmt = MediaFormat.createAudioFormat(info.mime, info.sampleRate, 1).apply {
                setInteger(MediaFormat.KEY_PCM_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
            }
            decoder.configure(fmt, null, null, 0)
            decoder.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var pos = magicLen
            var inputDone = false
            var outputDone = false
            var starve = 0

            var frameIndex = 0L

            while (!outputDone) {
                var progressed = false
                if (!inputDone) {
                    val inIdx = decoder.dequeueInputBuffer(0)
                    if (inIdx >= 0) {
                        progressed = true
                        val buf = decoder.getInputBuffer(inIdx)!!
                        buf.clear()
                        var fed = 0
                        while (pos < bytes.size && fed == 0) {
                            val ft = (bytes[pos].toInt() shr 3) and 0x0F
                            val size = if (ft in sizes.indices) sizes[ft] else 0
                            if (size <= 0) { pos = bytes.size; break }
                            if (size <= buf.remaining()) {
                                buf.put(bytes, pos, size)
                                fed = size
                                pos += size
                            } else break
                        }
                        if (fed > 0) {
                            decoder.queueInputBuffer(inIdx, 0, fed, frameIndex * FRAME_US, 0)
                            frameIndex++
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        }
                    }
                }
                when (val outIdx = decoder.dequeueOutputBuffer(bufferInfo, 0)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> progressed = true
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> progressed = true
                    else -> if (outIdx >= 0) {
                        progressed = true
                        if (bufferInfo.size > 0) {
                            val b = decoder.getOutputBuffer(outIdx)!!
                            b.position(bufferInfo.offset)
                            b.limit(bufferInfo.offset + bufferInfo.size)
                            val pts = bufferInfo.presentationTimeUs
                            if (pts >= fromUs && pts < untilUs) {
                                val chunk = ByteArray(bufferInfo.size)
                                b.get(chunk)
                                out.write(chunk)
                            } else if (pts >= untilUs) {
                                outputDone = true
                            }
                        }
                        val eos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        decoder.releaseOutputBuffer(outIdx, false)
                        if (eos) outputDone = true
                    }
                }
                if (!progressed) {
                    check(++starve < 10_000) { "amr decoder stalled" }
                    Thread.yield()
                } else starve = 0
            }
        } finally {
            runCatching { decoder.stop() }
            runCatching { decoder.release() }
        }
        return Triple(out.toByteArray(), info.sampleRate, 1)
    }

    private fun readAll(uri: Uri): ByteArray =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("cannot open $uri")
}
