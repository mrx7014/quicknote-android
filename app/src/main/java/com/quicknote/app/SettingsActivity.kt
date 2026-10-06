package com.quicknote.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.core.app.NotificationManagerCompat

class SettingsActivity : Activity() {
    private lateinit var popupSwitch: Switch
    private lateinit var overlayStatus: TextView
    private lateinit var overlayButton: Button
    private lateinit var notificationStatus: TextView
    private lateinit var languageCard: LinearLayout
    private lateinit var popupCard: LinearLayout
    private var suppressPopupToggle = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = PAGE
        window.navigationBarColor = PAGE
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        buildScreen()
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        if (AppPreferences.popupEnabled(this) && Settings.canDrawOverlays(this)) ensurePopupService()
    }

    private fun buildScreen() {
        val scroll = ScrollView(this).apply { setBackgroundColor(PAGE); clipToPadding = false }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = resources.configuration.layoutDirection
            setPadding(dp(21), dp(17), dp(21), dp(28))
        }
        scroll.addView(page, ViewGroup.LayoutParams(-1, -2))
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = resources.configuration.layoutDirection }
        val backGlyph = if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) "›" else "‹"
        val back = text(backGlyph, 34f, INK, true).apply {
            gravity = Gravity.CENTER
            contentDescription = getString(R.string.settings_back)
            background = rounded(Color.WHITE, dp(14), LINE)
            setOnClickListener { finish() }
        }
        header.addView(back, LinearLayout.LayoutParams(dp(46), dp(46)))
        val heading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        heading.addView(text(getString(R.string.settings_title), 23f, INK, true))
        heading.addView(text(getString(R.string.settings_subtitle), 13f, MUTED).apply { setPadding(0, dp(3), 0, 0) })
        header.addView(heading)
        page.addView(header)

        page.addView(sectionTitle(getString(R.string.language_section)))
        languageCard = card()
        languageCard.addView(text(getString(R.string.language_description), 13f, MUTED).apply { setPadding(0, 0, 0, dp(9)) })
        addLanguageRow(languageCard, "system", getString(R.string.language_system))
        addLanguageRow(languageCard, "en", getString(R.string.language_english))
        addLanguageRow(languageCard, "ar", getString(R.string.language_arabic))
        page.addView(languageCard)

        page.addView(sectionTitle(getString(R.string.popup_section)))
        popupCard = card()
        val popupHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = resources.configuration.layoutDirection }
        val popupText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, dp(8), 0) }
        popupText.addView(text(getString(R.string.popup_settings_title), 16f, INK, true))
        popupText.addView(text(getString(R.string.popup_settings_description), 12f, MUTED).apply { setPadding(0, dp(5), 0, 0) })
        popupHeader.addView(popupText, LinearLayout.LayoutParams(0, -2, 1f))
        popupSwitch = Switch(this).apply {
            text = getString(R.string.popup_switch)
            isChecked = AppPreferences.popupEnabled(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                if (!suppressPopupToggle) {
                    AppPreferences.setPopupEnabled(this@SettingsActivity, checked)
                    if (!checked) stopService(Intent(this@SettingsActivity, QuickCaptureService::class.java))
                    else if (!Settings.canDrawOverlays(this@SettingsActivity)) openOverlayPermission()
                    else ensurePopupService()
                    refreshStatus()
                }
            }
        }
        popupHeader.addView(popupSwitch)
        popupCard.addView(popupHeader)
        popupCard.addView(divider())
        overlayStatus = text("", 12f, MUTED).apply { setPadding(0, dp(11), 0, dp(10)) }
        popupCard.addView(overlayStatus)
        overlayButton = actionButton(getString(R.string.overlay_settings_button)) {
            if (Settings.canDrawOverlays(this@SettingsActivity)) previewPopup() else openOverlayPermission()
        }
        popupCard.addView(overlayButton, LinearLayout.LayoutParams(-1, dp(45)))
        page.addView(popupCard)

        page.addView(sectionTitle(getString(R.string.notification_settings_title)))
        val notificationCard = card()
        notificationCard.addView(text(getString(R.string.notification_settings_description), 13f, MUTED))
        notificationStatus = text("", 12f, TEAL, true).apply { setPadding(0, dp(9), 0, dp(9)) }
        notificationCard.addView(notificationStatus)
        notificationCard.addView(actionButton(getString(R.string.notification_settings_button)) { openNotificationSettings() }, LinearLayout.LayoutParams(-1, dp(45)))
        page.addView(notificationCard)

        page.addView(sectionTitle(getString(R.string.microphone_settings_title)))
        val microphoneCard = card()
        microphoneCard.addView(text(getString(R.string.microphone_settings_description), 13f, MUTED))
        microphoneCard.addView(actionButton(getString(R.string.microphone_settings_button)) { openAppSettings() }, LinearLayout.LayoutParams(-1, dp(45)).apply { topMargin = dp(10) })
        page.addView(microphoneCard)

        val privacyCard = card().apply { background = rounded(Color.rgb(230, 244, 237), dp(17)) }
        privacyCard.addView(text(getString(R.string.privacy_title), 15f, INK, true))
        privacyCard.addView(text(getString(R.string.privacy_description), 12f, MUTED).apply { setPadding(0, dp(6), 0, 0) })
        page.addView(privacyCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        page.addView(text(getString(R.string.version_label, BuildConfig.VERSION_NAME), 11f, MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(2))
        })
        setContentView(scroll)
    }

    private fun addLanguageRow(parent: LinearLayout, code: String, title: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = resources.configuration.layoutDirection
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(11), 0, dp(11), 0)
            background = rounded(if (currentLanguage() == code) Color.rgb(235, 247, 239) else Color.WHITE, dp(12), if (currentLanguage() == code) TEAL else LINE)
            setOnClickListener { setLanguage(code) }
        }
        row.addView(text(title, 14f, INK, currentLanguage() == code), LinearLayout.LayoutParams(0, dp(46), 1f))
        row.addView(text(if (currentLanguage() == code) "●" else "○", 17f, TEAL, true))
        parent.addView(row, LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(6) })
    }

    private fun currentLanguage(): String {
        if (Build.VERSION.SDK_INT >= 33) {
            val locales = getSystemService(android.app.LocaleManager::class.java).applicationLocales
            if (locales.isEmpty) return "system"
            return if (locales[0].language == "ar") "ar" else "en"
        }
        return AppPreferences.selectedLanguage(this)
    }

    private fun setLanguage(code: String) {
        if (currentLanguage() == code) return
        stopService(Intent(this, QuickCaptureService::class.java))
        if (Build.VERSION.SDK_INT >= 33) AppLocale.setFrameworkLocale(this, if (code == "system") "system" else code)
        else AppPreferences.preferences(this).edit().putString(AppPreferences.KEY_LANGUAGE, code).apply()
        val home = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(home)
        finish()
    }

    private fun refreshStatus() {
        if (!::overlayStatus.isInitialized) return
        val granted = Settings.canDrawOverlays(this)
        overlayStatus.text = getString(if (granted) R.string.overlay_allowed else R.string.overlay_not_allowed)
        overlayStatus.setTextColor(if (granted) TEAL else MUTED)
        overlayButton.text = getString(if (granted) R.string.overlay_allowed_button else R.string.overlay_settings_button)
        if (popupSwitch.isChecked != AppPreferences.popupEnabled(this)) {
            suppressPopupToggle = true
            popupSwitch.isChecked = AppPreferences.popupEnabled(this)
            suppressPopupToggle = false
        }
        val notificationsEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
        notificationStatus.text = getString(if (notificationsEnabled) R.string.status_enabled else R.string.status_disabled)
        notificationStatus.setTextColor(if (notificationsEnabled) TEAL else MUTED)
    }

    private fun openOverlayPermission() {
        AppPreferences.setPopupEnabled(this, true)
        suppressPopupToggle = true
        popupSwitch.isChecked = true
        suppressPopupToggle = false
        try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        catch (_: Exception) { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
    }

    private fun previewPopup() {
        AppPreferences.setPopupEnabled(this, true)
        suppressPopupToggle = true
        popupSwitch.isChecked = true
        suppressPopupToggle = false
        try {
            val intent = Intent(this, QuickCaptureService::class.java).setAction(QuickCaptureService.ACTION_SHOW_POPUP)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        } catch (_: Exception) { }
        refreshStatus()
    }

    private fun ensurePopupService() {
        try {
            val intent = Intent(this, QuickCaptureService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        } catch (_: Exception) { }
    }

    private fun openNotificationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= 26) Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        startActivity(intent)
    }

    private fun openAppSettings() = startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))

    private fun sectionTitle(value: String) = text(value, 11f, MUTED, true).apply { setPadding(dp(2), dp(20), dp(2), dp(8)) }
    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = resources.configuration.layoutDirection
        setPadding(dp(15), dp(14), dp(15), dp(14))
        background = rounded(Color.WHITE, dp(17), LINE)
        elevation = dp(1).toFloat()
    }
    private fun divider() = View(this).apply { setBackgroundColor(LINE) }.also { it.layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(12) } }
    private fun actionButton(title: String, click: () -> Unit) = Button(this).apply {
        text = title; isAllCaps = false; textSize = 13f; setTextColor(TEAL)
        background = rounded(Color.rgb(235, 247, 239), dp(12))
        setOnClickListener { click() }
    }
    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun rounded(fill: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply { setColor(fill); cornerRadius = radius.toFloat(); if (stroke != null) setStroke(dp(1), stroke) }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val PAGE = 0xFFF4F7F3.toInt()
        private const val INK = 0xFF24332E.toInt()
        private const val MUTED = 0xFF788780.toInt()
        private const val TEAL = 0xFF176B5B.toInt()
        private const val LINE = 0xFFE2EAE4.toInt()
    }
}
