package com.quicknote.app

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import android.provider.Settings
import java.util.Locale

object AppPreferences {
    private const val FILE = "quicknote_preferences"
    const val KEY_LANGUAGE = "app_language"
    const val KEY_UNLOCK_POPUP = "unlock_popup_enabled"

    fun preferences(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    fun popupEnabled(context: Context): Boolean {
        val prefs = preferences(context)
        // v1.1 had no explicit toggle. Preserve that user's opt-in special access on upgrade.
        return if (prefs.contains(KEY_UNLOCK_POPUP)) prefs.getBoolean(KEY_UNLOCK_POPUP, false)
        else Settings.canDrawOverlays(context)
    }
    fun setPopupEnabled(context: Context, enabled: Boolean) = preferences(context).edit().putBoolean(KEY_UNLOCK_POPUP, enabled).apply()
    fun selectedLanguage(context: Context) = preferences(context).getString(KEY_LANGUAGE, "system") ?: "system"
}

object AppLocale {
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val language = AppPreferences.selectedLanguage(base)
        if (language == "system") return base
        val locale = if (language == "ar") Locale("ar") else Locale.ENGLISH
        Locale.setDefault(locale)
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(locale)
        configuration.setLayoutDirection(locale)
        return base.createConfigurationContext(configuration)
    }

    fun systemLocale(context: Context): Locale {
        val locales = context.resources.configuration.locales
        return if (locales.isEmpty) Locale.getDefault() else locales[0]
    }

    fun setFrameworkLocale(context: Context, language: String) {
        if (Build.VERSION.SDK_INT >= 33) {
            val manager = context.getSystemService(android.app.LocaleManager::class.java)
            manager.applicationLocales = if (language == "system") LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(language)
        }
    }
}
