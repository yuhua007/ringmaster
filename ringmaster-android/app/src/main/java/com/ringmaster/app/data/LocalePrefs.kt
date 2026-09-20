package com.ringmaster.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * App 内语言偏好："" = 跟随系统；"zh" / "en" = 强制指定。
 */
object LocalePrefs {
    private const val PREFS = "locale_prefs"
    private const val KEY_LOCALE = "locale"

    fun load(context: Context): String {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return sp.getString(KEY_LOCALE, "") ?: ""
    }

    fun save(context: Context, locale: String) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit().putString(KEY_LOCALE, locale).apply()
    }
}
