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
    private const val KEY_DARK_MODE = "dark_mode"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_HIDE_CONTENT = "hide_notification_content"
    private const val KEY_APP_LOCK = "app_lock_enabled"
    private const val KEY_LOCK_TIMEOUT = "app_lock_timeout"
    private const val KEY_PROTECT_SCREEN = "protect_screen_content"
    private const val KEY_LAST_UNLOCK_POPUP = "last_unlock_popup_at"
    private const val KEY_RECOGNITION_LANGUAGE = "recognition_language"
    private const val KEY_SORT_MODE = "sort_mode"

    fun preferences(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun popupEnabled(context: Context) = preferences(context).getBoolean(KEY_UNLOCK_POPUP, false)
    fun setPopupEnabled(context: Context, enabled: Boolean) = preferences(context).edit().putBoolean(KEY_UNLOCK_POPUP, enabled).apply()

    fun selectedLanguage(context: Context): String {
        if (Build.VERSION.SDK_INT >= 33) {
            val locales = context.getSystemService(android.app.LocaleManager::class.java).applicationLocales
            if (locales.isEmpty) return "system"
            return if (locales[0].language == "ar") "ar" else "en"
        }
        return preferences(context).getString(KEY_LANGUAGE, "system") ?: "system"
    }

    fun themeMode(context: Context): String {
        val prefs = preferences(context)
        if (prefs.contains(KEY_THEME_MODE)) return prefs.getString(KEY_THEME_MODE, "system") ?: "system"
        if (!prefs.contains(KEY_DARK_MODE)) return "system"
        val migrated = if (prefs.getBoolean(KEY_DARK_MODE, false)) "dark" else "light"
        prefs.edit().putString(KEY_THEME_MODE, migrated).apply()
        return migrated
    }

    fun darkMode(context: Context): Boolean = when (themeMode(context)) {
        "dark" -> true
        "light" -> false
        else -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    fun setThemeMode(context: Context, mode: String) {
        require(mode in setOf("system", "light", "dark"))
        preferences(context).edit().putString(KEY_THEME_MODE, mode).remove(KEY_DARK_MODE).apply()
    }

    fun setDarkMode(context: Context, enabled: Boolean) = setThemeMode(context, if (enabled) "dark" else "light")
    fun hideNotificationContent(context: Context) = preferences(context).getBoolean(KEY_HIDE_CONTENT, false)
    fun setHideNotificationContent(context: Context, enabled: Boolean) = preferences(context).edit().putBoolean(KEY_HIDE_CONTENT, enabled).apply()
    fun appLockEnabled(context: Context) = preferences(context).getBoolean(KEY_APP_LOCK, false)
    fun setAppLockEnabled(context: Context, enabled: Boolean) = preferences(context).edit().putBoolean(KEY_APP_LOCK, enabled).apply()
    fun lockTimeoutMode(context: Context) = preferences(context).getString(KEY_LOCK_TIMEOUT, "30s") ?: "30s"
    fun setLockTimeoutMode(context: Context, mode: String) {
        require(mode in setOf("immediate", "30s", "1m", "5m"))
        preferences(context).edit().putString(KEY_LOCK_TIMEOUT, mode).apply()
    }
    fun lockTimeoutMillis(context: Context): Long = when (lockTimeoutMode(context)) {
        "immediate" -> 0L
        "1m" -> 60_000L
        "5m" -> 300_000L
        else -> 30_000L
    }
    fun protectScreenContent(context: Context) = preferences(context).getBoolean(KEY_PROTECT_SCREEN, false)
    fun setProtectScreenContent(context: Context, enabled: Boolean) = preferences(context).edit().putBoolean(KEY_PROTECT_SCREEN, enabled).apply()
    fun recognitionLanguage(context: Context) = preferences(context).getString(KEY_RECOGNITION_LANGUAGE, "app") ?: "app"
    fun setRecognitionLanguage(context: Context, language: String) {
        require(language in setOf("app", "en", "ar"))
        preferences(context).edit().putString(KEY_RECOGNITION_LANGUAGE, language).apply()
    }
    fun sortMode(context: Context) = preferences(context).getString(KEY_SORT_MODE, "updated") ?: "updated"
    fun setSortMode(context: Context, mode: String) {
        require(mode in setOf("updated", "created", "oldest"))
        preferences(context).edit().putString(KEY_SORT_MODE, mode).apply()
    }

    /** Prevents unlock prompts from repeatedly covering another app. Preview actions bypass this gate. */
    fun markUnlockPopupAllowed(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        if (!popupEnabled(context) || !Settings.canDrawOverlays(context)) return false
        val prefs = preferences(context)
        synchronized(this) {
            val previous = prefs.getLong(KEY_LAST_UNLOCK_POPUP, 0L)
            if (now - previous < UNLOCK_POPUP_COOLDOWN_MS) return false
            return prefs.edit().putLong(KEY_LAST_UNLOCK_POPUP, now).commit()
        }
    }

    fun setFrameworkLanguage(context: Context, language: String) {
        require(language in setOf("system", "en", "ar"))
        if (Build.VERSION.SDK_INT >= 33) {
            val manager = context.getSystemService(android.app.LocaleManager::class.java)
            manager.applicationLocales = if (language == "system") LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(language)
        } else preferences(context).edit().putString(KEY_LANGUAGE, language).apply()
    }

    private const val UNLOCK_POPUP_COOLDOWN_MS = 15 * 60 * 1000L
}

object AppLocale {
    fun wrap(base: Context): Context {
        val config = Configuration(base.resources.configuration)
        if (Build.VERSION.SDK_INT < 33) {
            val language = AppPreferences.selectedLanguage(base)
            if (language != "system") {
                val locale = if (language == "ar") Locale("ar") else Locale.ENGLISH
                Locale.setDefault(locale)
                config.setLocale(locale)
                config.setLayoutDirection(locale)
            }
        }
        when (AppPreferences.themeMode(base)) {
            "dark" -> config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
            "light" -> config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_NO
        }
        return base.createConfigurationContext(config)
    }

    fun uiLocale(context: Context): Locale {
        val locales = context.resources.configuration.locales
        return if (locales.isEmpty) Locale.getDefault() else locales[0]
    }

    @Deprecated("Use uiLocale; recording language is a separate preference")
    fun systemLocale(context: Context): Locale = uiLocale(context)
}
