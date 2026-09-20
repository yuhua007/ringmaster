package com.ringmaster.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 剪辑配方：导出铃声时记住「原始文件 + 选区 + 淡入淡出」，
 * 支撑铃声库"重新编辑"回到原始素材的编辑现场（而非只能二次剪辑成品）。
 * 存储为应用私有目录 JSON（MVP 简化，不上数据库）。
 */
data class ClipRecipe(
    val ringtoneId: Long,     // 关联的 MediaStore 铃声条目 id
    val sourceUri: String,    // 原始歌曲/视频的 uri（SAF persistable，重启可读）
    val sourceName: String,
    val startMs: Long,
    val endMs: Long,
    val fadeInMs: Long,
    val fadeOutMs: Long,
    val createdAt: Long,
    val miniWave: FloatArray? = null  // 选区段落的 60 点迷你波形（首页缩略图用）
)

object RecipeStore {

    private fun file(context: Context): File = File(context.filesDir, "recipes.json")

    fun save(context: Context, recipe: ClipRecipe) {
        runCatching {
            val list = readAll(context).filter { it.getLong("id") != recipe.ringtoneId }.toMutableList()
            list += JSONObject().apply {
                put("id", recipe.ringtoneId)
                put("sourceUri", recipe.sourceUri)
                put("sourceName", recipe.sourceName)
                put("startMs", recipe.startMs)
                put("endMs", recipe.endMs)
                put("fadeInMs", recipe.fadeInMs)
                put("fadeOutMs", recipe.fadeOutMs)
                put("createdAt", recipe.createdAt)
                recipe.miniWave?.let {
                    put("miniWave", org.json.JSONArray(it.toList()))
                }
            }
            file(context).writeText(JSONArray(list).toString())
        }
    }

    fun load(context: Context, ringtoneId: Long): ClipRecipe? = runCatching {
        readAll(context).firstOrNull { it.getLong("id") == ringtoneId }?.let { o ->
            ClipRecipe(
                ringtoneId = o.getLong("id"),
                sourceUri = o.getString("sourceUri"),
                sourceName = o.optString("sourceName", ""),
                startMs = o.getLong("startMs"),
                endMs = o.getLong("endMs"),
                fadeInMs = o.optLong("fadeInMs", 500),
                fadeOutMs = o.optLong("fadeOutMs", 500),
                createdAt = o.optLong("createdAt", 0),
                miniWave = o.optJSONArray("miniWave")?.let { arr ->
                    FloatArray(arr.length()) { k -> arr.optDouble(k).toFloat() }
                }
            )
        }
    }.getOrNull()

    fun remove(context: Context, ringtoneId: Long) {
        runCatching {
            val list = readAll(context).filter { it.getLong("id") != ringtoneId }
            file(context).writeText(JSONArray(list).toString())
        }
    }

    private fun readAll(context: Context): List<JSONObject> = runCatching {
        val f = file(context)
        if (!f.exists()) return emptyList()
        val arr = JSONArray(f.readText())
        (0 until arr.length()).map { arr.getJSONObject(it) }
    }.getOrDefault(emptyList())
}
