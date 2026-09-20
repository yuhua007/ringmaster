package com.ringmaster.app.data

import android.content.Context
import java.io.File

/**
 * 缓存临时文件清理：导出（cut_）、试听转码（pv_）、格式自测（probe_）
 * 每次操作都会在 cacheDir 留下文件，长期使用会堆积，启动时按龄清理。
 */
object CacheCleaner {

    private val TEMP_PREFIXES = arrayOf("cut_", "pv_", "probe_")

    /** 删除超过 [maxAgeMs]（默认 24h）的本应用临时文件。挂 IO 线程调用。 */
    fun clean(context: Context, maxAgeMs: Long = 24 * 3600 * 1000L) {
        val now = System.currentTimeMillis()
        context.cacheDir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.startsWithAny(TEMP_PREFIXES) && now - f.lastModified() > maxAgeMs) {
                f.delete()
            }
        }
    }

    private fun String.startsWithAny(prefixes: Array<String>): Boolean =
        prefixes.any { startsWith(it) }
}
