package com.ringmaster.app.data

import android.content.ContentUris
import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/**
 * 铃声库数据层：查询/删除本应用保存到系统铃声目录（各铃声类型下的 RingMaster 子目录）的铃声。
 */
object RingtoneLibrary {

    data class Item(
        val id: Long,
        val uri: Uri,
        val title: String,
        val durationMs: Long
    )

    private val subDirSelection: String
        get() = if (Build.VERSION.SDK_INT >= 29) {
            "(${MediaStore.MediaColumns.RELATIVE_PATH}=? OR " +
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? OR " +
                "${MediaStore.MediaColumns.RELATIVE_PATH}=?)"
        } else {
            "${MediaStore.MediaColumns.DATA} LIKE ?"
        }

    private val subDirArgs: Array<String>
        get() = if (Build.VERSION.SDK_INT >= 29) {
            arrayOf(
                "${android.os.Environment.DIRECTORY_RINGTONES}/RingMaster/",
                "${android.os.Environment.DIRECTORY_NOTIFICATIONS}/RingMaster/",
                "${android.os.Environment.DIRECTORY_ALARMS}/RingMaster/"
            )
        } else {
            arrayOf("%/RingMaster/%")
        }

    /** 按添加时间倒序列出本应用的铃声。挂 IO 线程调用。 */
    fun query(context: Context): List<Item> {
        val list = mutableListOf<Item>()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DURATION
            ),
            subDirSelection + " AND ${MediaStore.Audio.AudioColumns.IS_RINGTONE}=1",
            subDirArgs,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val durCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
            while (c.moveToNext()) {
                val name = c.getString(nameCol) ?: continue
                list += Item(
                    id = c.getLong(idCol),
                    uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(idCol)),
                    title = name.substringBeforeLast('.').removePrefix("RM "),
                    durationMs = if (durCol >= 0) c.getLong(durCol) else 0L
                )
            }
        }
        return list
    }

    /** 删除铃声（均为本应用创建的媒体，API 29+ 免权限）。 */
    fun delete(context: Context, item: Item): Boolean = try {
        context.contentResolver.delete(item.uri, null, null) > 0
    } catch (e: SecurityException) {
        false
    }

    /** 重命名（铃声库列表入口）。 */
    fun rename(context: Context, item: Item, newTitle: String): Boolean =
        renameByUri(context, item.uri, newTitle)

    /** 按 uri 重命名（导出面板入口）。 */
    fun renameByUri(context: Context, uri: Uri, newTitle: String): Boolean = try {
        val values = android.content.ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$newTitle.m4a")
            put(MediaStore.MediaColumns.TITLE, newTitle)
        }
        context.contentResolver.update(uri, values, null, null) > 0
    } catch (e: SecurityException) {
        false
    }

    /** 当前系统默认铃声 Uri（用于列表标记）。 */
    fun currentDefault(context: Context): Uri? =
        RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
}
