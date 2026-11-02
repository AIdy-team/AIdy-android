package com.aidy.petrobot.data

import android.content.Context
import com.aidy.petrobot.AidyApplication

/** Unity の PlayerPrefs 相当 (キーも同じ)。 */
object Prefs {
    private val sp by lazy { AidyApplication.context.getSharedPreferences("aidy", Context.MODE_PRIVATE) }

    fun getString(key: String, def: String): String = sp.getString(key, def) ?: def
    fun putString(key: String, value: String) = sp.edit().putString(key, value).apply()
    fun getInt(key: String, def: Int) = sp.getInt(key, def)
    fun putInt(key: String, value: Int) = sp.edit().putInt(key, value).apply()
}
