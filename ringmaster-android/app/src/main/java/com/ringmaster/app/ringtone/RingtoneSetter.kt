package com.ringmaster.app.ringtone

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream

/**
 * Spike①：一键设为铃声的完整链路。
 *
 * 验证点：
 * 1. WRITE_SETTINGS 特殊权限：Settings.System.canWrite() 检查 + 跳系统授权页
 * 2. 写入系统铃声目录：API 29+ 走 MediaStore（免存储权限），API 26-28 走公共目录 + 媒体扫描
 * 3. RingtoneManager.setActualDefaultRingtoneUri 真正生效
 */
object RingtoneSetter {

    /** 三种铃声类型。 */
    enum class Type(val ringtoneType: Int, val dirName: String) {
        RINGTONE(RingtoneManager.TYPE_RINGTONE, Environment.DIRECTORY_RINGTONES),
        NOTIFICATION(RingtoneManager.TYPE_NOTIFICATION, Environment.DIRECTORY_NOTIFICATIONS),
        ALARM(RingtoneManager.TYPE_ALARM, Environment.DIRECTORY_ALARMS)
    }

    fun canWriteSettings(context: Context): Boolean = Settings.System.canWrite(context)

    /** 跳转系统「允许修改系统设置」授权页（用户手动开启，无法静默授予）。 */
    fun requestWriteSettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    /**
     * 把音频文件复制进系统铃声目录，返回 MediaStore content Uri。
     * API 29+：MediaStore 直插（应用自建媒体免权限）。
     * API 26-28：公共 Ringtones 目录 + MediaScanner（需 WRITE_EXTERNAL_STORAGE 运行时授权）。
     */
    fun saveToRingtoneDir(context: Context, source: File, displayName: String, type: Type): Uri {
        val mime = "audio/${source.extension.lowercase().let {
            if (it == "m4a" || it == "mp4") "mp4" else MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) ?: "mpeg"
        }}"
        return if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$displayName.${source.extension}")
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${type.dirName}/RingMaster")
                put(MediaStore.Audio.AudioColumns.IS_RINGTONE, 1)
                put(MediaStore.Audio.AudioColumns.IS_NOTIFICATION, 1)
                put(MediaStore.Audio.AudioColumns.IS_ALARM, 1)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = context.contentResolver.insert(collection, values)
                ?: error("MediaStore insert failed")
            context.contentResolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            } ?: error("open output stream failed")
            context.contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
            uri
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(type.dirName), "RingMaster")
            dir.mkdirs()
            val dest = File(dir, "$displayName.${source.extension}")
            source.copyTo(dest, overwrite = true)
            // API<29 标准做法：按文件路径插入 MediaStore 索引，返回 content Uri
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DATA, dest.absolutePath)
                put(MediaStore.MediaColumns.DISPLAY_NAME, dest.name)
                put(MediaStore.MediaColumns.TITLE, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.Audio.AudioColumns.IS_RINGTONE, 1)
                put(MediaStore.Audio.AudioColumns.IS_NOTIFICATION, 1)
                put(MediaStore.Audio.AudioColumns.IS_ALARM, 1)
            }
            context.contentResolver.insert(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values
            ) ?: Uri.fromFile(dest)
        }
    }

    /** 设为系统默认（前提：canWriteSettings == true）。返回是否成功。 */
    fun setDefault(context: Context, uri: Uri, type: Type): Boolean = try {
        RingtoneManager.setActualDefaultRingtoneUri(context, type.ringtoneType, uri)
        true
    } catch (e: SecurityException) {
        false
    }

    /**
     * 设为指定联系人的专属铃声（Android 独有能力）。
     * [contactUri] 来自系统联系人选择器（PickContact），仅写该联系人的单条记录，不读通讯录。
     * 前提：已授予 WRITE_CONTACTS。
     */
    fun setForContact(context: Context, contactUri: Uri, ringtoneUri: Uri): Boolean = try {
        val values = ContentValues().apply {
            put(android.provider.ContactsContract.Contacts.CUSTOM_RINGTONE, ringtoneUri.toString())
        }
        context.contentResolver.update(contactUri, values, null, null) > 0
    } catch (e: SecurityException) {
        false
    }
}
