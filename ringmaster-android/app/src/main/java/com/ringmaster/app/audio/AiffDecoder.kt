package com.ringmaster.app.audio

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.pow

/**
 * AIFF 解码（.aiff/.aif：Mac/iTunes 经典无损格式）。
 * 系统 MediaExtractor 不支持 AIFF，此处自写容器解析（FORM/AIFF + COMM + SSND），
 * 支持 8/16/24bit 大端 PCM（AIFC 压缩变体不支持），输出 16bit 小端。
 */
class AiffDecoder(private val context: Context) {

    data class AiffInfo(val channels: Int, val bits: Int, val sampleRate: Int, val numFrames: Long)

    /** 嗅探文件头与 COMM 参数；非支持 AIFF 返回 null。 */
    fun sniff(uri: Uri): AiffInfo? = runCatching {
        val input = BufferedInputStream(context.contentResolver.openInputStream(uri) ?: return null)
        input.use { ins ->
            val head = ByteArray(12)
            if (ins.read(head) != 12) return null
            if (!head.isAscii(0, "FORM") || !head.isAscii(8, "AIFF")) return null

            var channels = 0; var bits = 0; var rate = 0; var frames = 0L
            var found = false
            while (!found) {
                val id = ByteArray(4); val sizeB = ByteArray(4)
                if (ins.read(id) != 4 || ins.read(sizeB) != 4) break
                val size = readIntBE(sizeB, 0)
                if (id.isAscii(0, "COMM")) {
                    val payload = ByteArray(size.coerceAtMost(64))
                    ins.read(payload)
                    channels = readShortBE(payload, 0)
                    frames = readIntBE(payload, 2).toLong() and 0xFFFFFFFFL
                    bits = readShortBE(payload, 6)
                    rate = readExtended80(payload, 8).toInt()
                    found = true
                } else {
                    skip(ins, size.toLong() + (size % 2))
                }
            }
            if (!found || channels <= 0 || rate <= 0 || (bits != 8 && bits != 16 && bits != 24)) null
            else AiffInfo(channels, bits, rate, frames)
        }
    }.getOrNull()

    fun durationMs(info: AiffInfo): Long = if (info.sampleRate > 0) info.numFrames * 1000L / info.sampleRate else 0

    /** 解码 [fromUs, untilUs) 为 16bit LE 交错 PCM。 */
    fun decode(uri: Uri, info: AiffInfo, fromUs: Long, untilUs: Long): ByteArray {
        val input = BufferedInputStream(context.contentResolver.openInputStream(uri) ?: error("cannot open"))
        input.use { ins ->
            skip(ins, 12)
            while (true) {
                val id = ByteArray(4); val sizeB = ByteArray(4)
                if (ins.read(id) != 4 || ins.read(sizeB) != 4) error("no SSND chunk")
                val size = readIntBE(sizeB, 0)
                if (id.isAscii(0, "SSND")) {
                    val hdr = ByteArray(8)
                    if (ins.read(hdr) != 8) error("bad SSND")
                    skip(ins, readIntBE(hdr, 0).toLong())
                    return readPcmRange(ins, info, fromUs, untilUs)
                }
                skip(ins, size.toLong() + (size % 2))
            }
        }
    }

    /** 流式读取并转换：仅保留区间内帧，支持帧跨读缓冲边界。 */
    private fun readPcmRange(ins: InputStream, info: AiffInfo, fromUs: Long, untilUs: Long): ByteArray {
        val bytesPerSample = info.bits / 8
        val frameBytes = info.channels * bytesPerSample
        val out = ByteArrayOutputStream(1 shl 20)
        val buf = ByteArray(64 * 1024 + frameBytes)
        var start = 0
        var frameIndex = 0L
        while (true) {
            val n = ins.read(buf, start, buf.size - start)
            if (n < 0) break
            val total = start + n
            var p = 0
            while (p + frameBytes <= total) {
                val ptsUs = frameIndex * 1_000_000L / info.sampleRate
                if (ptsUs >= fromUs && ptsUs < untilUs) {
                    for (c in 0 until info.channels) {
                        val s = convertSample(buf, p + c * bytesPerSample, info.bits)
                        out.write(s and 0xFF)
                        out.write((s shr 8) and 0xFF)
                    }
                }
                p += frameBytes
                frameIndex++
            }
            val remain = total - p
            if (remain > 0) System.arraycopy(buf, p, buf, 0, remain)
            start = remain
        }
        return out.toByteArray()
    }

    /** 大端 → 有符号 16bit。 */
    private fun convertSample(b: ByteArray, off: Int, bits: Int): Int = when (bits) {
        8 -> ((b[off].toInt() and 0xFF) - 128) shl 8
        16 -> (b[off].toInt() shl 8) or (b[off + 1].toInt() and 0xFF)
        24 -> (((b[off].toInt() and 0xFF) shl 16) or ((b[off + 1].toInt() and 0xFF) shl 8) or (b[off + 2].toInt() and 0xFF)) shr 8
        else -> 0
    }

    private fun skip(ins: InputStream, n: Long) {
        var remaining = n
        while (remaining > 0) {
            val skipped = ins.skip(remaining)
            if (skipped <= 0) { if (ins.read() < 0) return; remaining-- } else remaining -= skipped
        }
    }

    private fun readIntBE(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

    private fun readShortBE(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    /** AIFF 采样率：80bit IEEE 754 extended float。 */
    private fun readExtended80(b: ByteArray, off: Int): Double {
        val exp = ((b[off].toInt() and 0x7F) shl 8) or (b[off + 1].toInt() and 0xFF)
        var mantissa = 0L
        for (i in 2 until 10) mantissa = (mantissa shl 8) or (b[off + i].toInt() and 0xFF).toLong()
        return mantissa.toDouble() / 9_223_372_036_854_775_808.0 * 2.0.pow((exp - 16383).toDouble())
    }

    private fun ByteArray.isAscii(off: Int, s: String): Boolean =
        s.indices.all { this[off + it] == s[it].code.toByte() }
}
