package com.ringmaster.app.audio

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 格式自测：对一个文件依次执行容器识别/解码/波形/剪切四段探针，
 * 输出人类可读报告，定位"读不了"具体断在哪一环。
 */
object FormatProbe {

    data class Report(val text: String, val allPassed: Boolean)

    suspend fun probe(context: Context, uri: Uri): Report = withContext(Dispatchers.Default) {
        val sb = StringBuilder()
        var ok = 0
        var fail = 0

        fun line(step: String, passed: Boolean, detail: String) {
            if (passed) ok++ else fail++
            sb.append(if (passed) "[通过] " else "[失败] ")
                .append(step).append(" — ").append(detail).append('\n')
        }

        // 0. 基本信息
        runCatching {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "?"
            val size = context.contentResolver.openInputStream(uri)?.use { it.available() } ?: 0
            sb.append("文件: $name (${size / 1024}KB)\n")
        }

        // 1. 容器识别
        val amrInfo = runCatching { AmrDecoder(context).sniff(uri) }.getOrNull()
        var extractorInfo: String? = null
        var trackMime: String? = null
        runCatching {
            val ex = MediaExtractor()
            try {
                ex.setDataSource(context, uri, null)
                if (ex.trackCount == 0) error("0 条轨道")
                val fmt = ex.getTrackFormat(0)
                trackMime = fmt.getString(MediaFormat.KEY_MIME)
                val sr = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else -1
                val ch = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else -1
                val durMs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) / 1000 else -1
                extractorInfo = "mime=$trackMime sr=$sr ch=$ch dur=${if (durMs >= 0) "${durMs / 1000}s" else "未知"}"
            } finally {
                ex.release()
            }
        }.onFailure { extractorInfo = "异常: ${it.javaClass.simpleName}: ${it.message}" }

        if (amrInfo != null) {
            line("容器识别(AMR)", true, "裸 AMR ${if (amrInfo.isWb) "WB 16k" else "NB 8k"}，系统提取器不认但自写解析可读")
        } else if (extractorInfo?.startsWith("异常") != true && extractorInfo != null) {
            line("容器识别", true, extractorInfo)
        } else {
            line("容器识别", false, extractorInfo ?: "未知")
        }

        // 2. 解码 + 波形（全曲）
        runCatching {
            val t0 = System.currentTimeMillis()
            val wf = WaveformExtractor(context).extract(uri).getOrThrow()
            line("解码/波形", true, "${wf.amplitudes.size} 桶 · ${wf.durationMs / 1000}s · ${System.currentTimeMillis() - t0}ms")
        }.onFailure { line("解码/波形", false, "${it.javaClass.simpleName}: ${it.message}") }

        // 3. 剪切导出（前 3 秒 → m4a）
        runCatching {
            val dest = File(context.cacheDir, "probe_${System.currentTimeMillis()}.m4a")
            val t0 = System.currentTimeMillis()
            val r = AudioCutter(context).cut(uri, 0, 3000, fadeInMs = 0, fadeOutMs = 0, dest = dest).getOrThrow()
            line("剪切导出", true, "${r.durationMs}ms m4a · 解码${r.decodeMs}ms 编码${r.encodeMs}ms")
            dest.delete()
        }.onFailure { line("剪切导出", false, "${it.javaClass.simpleName}: ${it.message}") }

        Report(sb.toString().trimEnd(), fail == 0)
    }
}
