package com.ringmaster.app.ringtone

import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/**
 * Spike① 辅助：程序化生成一段正弦波 WAV 测试音频。
 * 真机上不依赖任何素材文件即可验证「保存到铃声目录 + 设为系统铃声」链路。
 */
object TestToneGenerator {

    private const val SAMPLE_RATE = 44100
    private const val DURATION_SECONDS = 3

    /** 生成 440Hz→880Hz 交替鸣响的测试音，写入 [destFile]，返回文件。 */
    fun generate(destFile: File): File {
        val totalSamples = SAMPLE_RATE * DURATION_SECONDS
        val pcm = ShortArray(totalSamples) { i ->
            val t = i.toDouble() / SAMPLE_RATE
            // 每半秒在 440/880Hz 间切换，便于来电时听出是测试音
            val freq = if ((i / (SAMPLE_RATE / 2)) % 2 == 0) 440.0 else 880.0
            val envelope = minOf(1.0, (totalSamples - i).toDouble() / SAMPLE_RATE * 0.5) // 结尾 0.5s 淡出防爆音
            (sin(2 * PI * freq * t) * 0.6 * envelope * Short.MAX_VALUE).toInt().toShort()
        }

        val bytes = ByteArrayOutputStream(44 + totalSamples * 2).apply {
            val header = ByteArray(44)
            val put = { off: Int, v: Int -> header[off] = (v and 0xFF).toByte(); header[off + 1] = ((v shr 8) and 0xFF).toByte() }
            "RIFF".toByteArray().copyInto(header, 0)
            val dataSize = totalSamples * 2
            putIntLE(header, 4, 36 + dataSize)
            "WAVE".toByteArray().copyInto(header, 8)
            "fmt ".toByteArray().copyInto(header, 12)
            putIntLE(header, 16, 16)
            header[20] = 1; header[21] = 0              // PCM
            header[22] = 1; header[23] = 0              // mono
            putIntLE(header, 24, SAMPLE_RATE)
            putIntLE(header, 28, SAMPLE_RATE * 2)       // byte rate
            header[32] = 2; header[33] = 0              // block align
            header[34] = 16; header[35] = 0             // bits per sample
            "data".toByteArray().copyInto(header, 36)
            putIntLE(header, 40, dataSize)
            write(header)
            val buf = ByteArray(totalSamples * 2)
            for (i in 0 until totalSamples) {
                buf[i * 2] = (pcm[i].toInt() and 0xFF).toByte()
                buf[i * 2 + 1] = ((pcm[i].toInt() shr 8) and 0xFF).toByte()
            }
            write(buf)
        }
        destFile.parentFile?.mkdirs()
        destFile.writeBytes(bytes.toByteArray())
        return destFile
    }

    private fun putIntLE(dst: ByteArray, off: Int, v: Int) {
        dst[off] = (v and 0xFF).toByte()
        dst[off + 1] = ((v shr 8) and 0xFF).toByte()
        dst[off + 2] = ((v shr 16) and 0xFF).toByte()
        dst[off + 3] = ((v shr 24) and 0xFF).toByte()
    }
}
