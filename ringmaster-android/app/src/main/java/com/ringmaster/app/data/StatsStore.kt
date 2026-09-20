package com.ringmaster.app.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/**
 * 本地行为计数（成就系统/留存分析的数据基础，Phase 4 消费）。
 * 全部本地存储，不联网不上报——符合隐私最小化承诺。
 */
object StatsStore {

    const val KEY_RINGTONES = "ringtones_created"  // 制作铃声数
    const val KEY_SHARES = "shares"               // 分享数
    const val KEY_RINGS_SET = "rings_set"         // 设置为系统铃声次数
    const val KEY_AI_USED = "ai_used"             // AI 使用次数（Phase 3 埋）
    const val KEY_ACTIVE_DAYS = "active_days"     // 活跃天数

    @Volatile
    private var cache: JSONObject? = null

    private fun file(context: Context): File = File(context.filesDir, "stats.json")

    private fun load(context: Context): JSONObject {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val o = runCatching {
                val f = file(context)
                if (f.exists()) JSONObject(f.readText()) else JSONObject()
            }.getOrDefault(JSONObject())
            cache = o
            return o
        }
    }

    private fun persist(context: Context, o: JSONObject) {
        runCatching { file(context).writeText(o.toString()) }
    }

    fun get(context: Context, key: String): Long = load(context).optLong(key, 0)

    fun record(context: Context, key: String, delta: Int = 1) {
        synchronized(this) {
            val o = load(context)
            o.put(key, o.optLong(key, 0) + delta)
            persist(context, o)
        }
    }

    /** 活跃天数：跨自然日才 +1（当天多次启动只算一天）。 */
    fun recordActive(context: Context) {
        synchronized(this) {
            val o = load(context)
            val today = LocalDate.now().toString()
            if (o.optString("last_active_day", "") != today) {
                o.put(KEY_ACTIVE_DAYS, o.optLong(KEY_ACTIVE_DAYS, 0) + 1)
                o.put("last_active_day", today)
                persist(context, o)
            }
        }
    }
}
