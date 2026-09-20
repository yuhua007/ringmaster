package com.ringmaster.app

import android.app.Application
import android.content.Context
import java.io.File
import java.util.Locale

/**
 * 全局崩溃兜底：堆栈写本地 crash_log.txt（128KB 滚动），不影响系统默认崩溃流程。
 * Phase 4 上架前再决定是否接 Crashlytics。
 */
class RingMasterApp : Application() {
    /** 应用级协程作用域（生命周期随进程，替代已弃用的 GlobalScope） */
    val appScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    override fun onCreate() {
        super.onCreate()
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { CrashLog.write(this, thread, throwable) }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}

object CrashLog {
    private const val MAX_BYTES = 128 * 1024
    private const val NL = "\\n" // 源码级换行转义（避免工具链转义层吞反斜杠）

    fun write(context: Context, thread: Thread, t: Throwable) {
        val f = File(context.filesDir, "crash_log.txt")
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(java.util.Date())
        val entry = NL + "==== " + stamp + " thread=" + thread.name + " ====" + NL +
            android.util.Log.getStackTraceString(t) + NL
        if (f.exists() && f.length() > MAX_BYTES) {
            f.writeText(f.readText().takeLast(MAX_BYTES / 2) + entry)
        } else {
            f.appendText(entry)
        }
    }
}
