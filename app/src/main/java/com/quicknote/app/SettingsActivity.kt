package com.quicknote.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.fragment.app.FragmentActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : FragmentActivity() {
    private lateinit var popupSwitch: Switch
    private lateinit var overlayStatus: TextView
    private lateinit var overlayButton: Button
    private lateinit var notificationStatus: TextView
    private lateinit var root: FrameLayout
    private lateinit var lockSwitch: Switch
    private var lockCover: View? = null
    private var lockPromptActive = false
    private var suppressPopupToggle = false
    private val palette get() = AppPalette.from(this)
    private val PAGE get() = palette.page
    private val INK get() = palette.ink
    private val MUTED get() = palette.muted
    private val TEAL get() = palette.teal
    private val LINE get() = palette.line

    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(AppLocale.wrap(newBase)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSystemColors()
        if (AppPreferences.appLockEnabled(this)) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        buildScreen(); refreshStatus()
    }

    override fun onResume() {
        super.onResume(); guardAppLock(); refreshStatus()
        if (AppPreferences.popupEnabled(this) && android.provider.Settings.canDrawOverlays(this)) ensurePopupService()
    }

    private fun setSystemColors() {
        window.statusBarColor = PAGE; window.navigationBarColor = PAGE
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (palette.dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    }

    private fun buildScreen() {
        val scroll = ScrollView(this).apply { setBackgroundColor(PAGE); clipToPadding = false }
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = resources.configuration.layoutDirection; setPadding(dp(21), dp(17), dp(21), dp(28)) }
        scroll.addView(page, ViewGroup.LayoutParams(-1, -2))
        root = FrameLayout(this).apply { setBackgroundColor(PAGE); addView(scroll, FrameLayout.LayoutParams(-1, -1)) }
        setContentView(root)
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = resources.configuration.layoutDirection }
        val glyph = if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) "›" else "‹"
        header.addView(text(glyph, 34f, INK, true).apply {
            gravity = Gravity.CENTER; contentDescription = getString(R.string.settings_back); background = rounded(palette.surface, dp(14), LINE); setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(46), dp(46)))
        val heading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        heading.addView(text(getString(R.string.settings_title), 23f, INK, true)); heading.addView(text(getString(R.string.settings_subtitle), 13f, MUTED).apply { setPadding(0, dp(3), 0, 0) })
        header.addView(heading); page.addView(header)

        page.addView(sectionTitle(getString(R.string.language_section)))
        val languageCard = card(); languageCard.addView(text(getString(R.string.language_description), 13f, MUTED).apply { setPadding(0, 0, 0, dp(9)) })
        addLanguageRow(languageCard, "system", getString(R.string.language_system)); addLanguageRow(languageCard, "en", getString(R.string.language_english)); addLanguageRow(languageCard, "ar", getString(R.string.language_arabic)); page.addView(languageCard)

        page.addView(sectionTitle(getString(R.string.popup_section)))
        val popupCard = card(); val popupHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = resources.configuration.layoutDirection }
        val popupText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, dp(8), 0) }
        popupText.addView(text(getString(R.string.popup_settings_title), 16f, INK, true)); popupText.addView(text(getString(R.string.popup_settings_description), 12f, MUTED).apply { setPadding(0, dp(5), 0, 0) })
        popupHeader.addView(popupText, LinearLayout.LayoutParams(0, -2, 1f))
        popupSwitch = Switch(this).apply {
            text = getString(R.string.popup_switch); isChecked = AppPreferences.popupEnabled(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked -> if (!suppressPopupToggle) {
                AppPreferences.setPopupEnabled(this@SettingsActivity, checked)
                if (!checked) stopService(Intent(this@SettingsActivity, QuickCaptureService::class.java))
                else if (!android.provider.Settings.canDrawOverlays(this@SettingsActivity)) openOverlayPermission() else ensurePopupService()
                refreshStatus()
            } }
        }
        popupHeader.addView(popupSwitch); popupCard.addView(popupHeader); popupCard.addView(divider())
        overlayStatus = text("", 12f, MUTED).apply { setPadding(0, dp(11), 0, dp(10)) }; popupCard.addView(overlayStatus)
        overlayButton = actionButton(getString(R.string.overlay_settings_button)) { if (android.provider.Settings.canDrawOverlays(this@SettingsActivity)) previewPopup() else openOverlayPermission() }
        popupCard.addView(overlayButton, LinearLayout.LayoutParams(-1, dp(45))); page.addView(popupCard)

        page.addView(sectionTitle(getString(R.string.appearance_privacy_section)))
        val appearanceCard = card()
        appearanceCard.addView(switchRow(getString(R.string.dark_mode_title), getString(R.string.dark_mode_description), AppPreferences.darkMode(this)) { enabled ->
            AppPreferences.setDarkMode(this, enabled); QuickNoteWidgetProvider.refreshAll(this); restartHome()
        })
        appearanceCard.addView(divider())
        appearanceCard.addView(switchRow(getString(R.string.hide_notifications_title), getString(R.string.hide_notifications_description), AppPreferences.hideNotificationContent(this)) { enabled ->
            AppPreferences.setHideNotificationContent(this, enabled); refreshSavedNotifications()
        })
        appearanceCard.addView(divider())
        lockSwitch = Switch(this).apply {
            text = getString(R.string.app_lock_title); isChecked = AppPreferences.appLockEnabled(this@SettingsActivity)
            setOnCheckedChangeListener { _, enabled -> onLockSwitchChanged(enabled) }
        }
        appearanceCard.addView(settingRow(getString(R.string.app_lock_title), getString(R.string.app_lock_description), lockSwitch))
        page.addView(appearanceCard)

        page.addView(sectionTitle(getString(R.string.backup_section)))
        val backupCard = card(); backupCard.addView(text(getString(R.string.backup_description), 13f, MUTED))
        val backupActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = resources.configuration.layoutDirection }
        backupActions.addView(actionButton(getString(R.string.backup_export)) { exportBackup() }, LinearLayout.LayoutParams(0, dp(45), 1f).apply { marginEnd = dp(7); topMargin = dp(11) })
        backupActions.addView(actionButton(getString(R.string.backup_import)) { confirmImport() }, LinearLayout.LayoutParams(0, dp(45), 1f).apply { topMargin = dp(11) })
        backupCard.addView(backupActions); page.addView(backupCard)

        page.addView(sectionTitle(getString(R.string.quick_access_section)))
        val quickCard = card(); quickCard.addView(text(getString(R.string.quick_access_description), 13f, MUTED)); page.addView(quickCard)

        page.addView(sectionTitle(getString(R.string.notification_settings_title)))
        val notificationCard = card(); notificationCard.addView(text(getString(R.string.notification_settings_description), 13f, MUTED))
        notificationStatus = text("", 12f, TEAL, true).apply { setPadding(0, dp(9), 0, dp(9)) }; notificationCard.addView(notificationStatus)
        notificationCard.addView(actionButton(getString(R.string.notification_settings_button)) { openNotificationSettings() }, LinearLayout.LayoutParams(-1, dp(45))); page.addView(notificationCard)

        page.addView(sectionTitle(getString(R.string.microphone_settings_title)))
        val microphoneCard = card(); microphoneCard.addView(text(getString(R.string.microphone_settings_description), 13f, MUTED))
        microphoneCard.addView(actionButton(getString(R.string.microphone_settings_button)) { openAppSettings() }, LinearLayout.LayoutParams(-1, dp(45)).apply { topMargin = dp(10) }); page.addView(microphoneCard)

        val privacyCard = card().apply { background = rounded(palette.panelTint, dp(17)) }
        privacyCard.addView(text(getString(R.string.privacy_title), 15f, INK, true)); privacyCard.addView(text(getString(R.string.privacy_description), 12f, MUTED).apply { setPadding(0, dp(6), 0, 0) })
        page.addView(privacyCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        page.addView(text(getString(R.string.version_label, BuildConfig.VERSION_NAME), 11f, MUTED).apply { gravity = Gravity.CENTER; setPadding(0, dp(16), 0, dp(2)) })
        if (AppPreferences.appLockEnabled(this)) attachLockCover()
    }

    private fun switchRow(title: String, description: String, checked: Boolean, changed: (Boolean) -> Unit): View {
        val toggle = Switch(this).apply { isChecked = checked; setOnCheckedChangeListener { _, value -> changed(value) } }
        return settingRow(title, description, toggle)
    }

    private fun settingRow(title: String, description: String, toggle: Switch): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = resources.configuration.layoutDirection }
        val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), dp(8), dp(4)) }
        copy.addView(text(title, 14f, INK, true)); copy.addView(text(description, 11f, MUTED).apply { setPadding(0, dp(4), 0, 0) })
        row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f)); row.addView(toggle); return row
    }

    private fun addLanguageRow(parent: LinearLayout, code: String, title: String) {
        val selected = currentLanguage() == code
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; layoutDirection = resources.configuration.layoutDirection; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(11), 0, dp(11), 0)
            background = rounded(if (selected) palette.panelTint else palette.surface, dp(12), if (selected) TEAL else LINE); setOnClickListener { setLanguage(code) }
        }
        row.addView(text(title, 14f, INK, selected), LinearLayout.LayoutParams(0, dp(46), 1f)); row.addView(text(if (selected) "●" else "○", 17f, TEAL, true))
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
        QuickNoteWidgetProvider.refreshAll(this)
        restartHome()
    }

    private fun onLockSwitchChanged(enabled: Boolean) {
        if (!enabled) {
            AppPreferences.setAppLockEnabled(this, false); AppLockSession.authenticated = true; window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE); return
        }
        if (!AppLockHelper.supported(this)) {
            lockSwitch.isChecked = false; Toast.makeText(this, getString(R.string.lock_unavailable), Toast.LENGTH_LONG).show(); return
        }
        lockSwitch.isEnabled = false
        AppLockHelper.authenticate(this, {
            AppPreferences.setAppLockEnabled(this, true); AppLockSession.authenticated = true; window.addFlags(WindowManager.LayoutParams.FLAG_SECURE); lockSwitch.isEnabled = true
        }, { message ->
            AppPreferences.setAppLockEnabled(this, false); lockSwitch.isChecked = false; lockSwitch.isEnabled = true
            if (message.isNotBlank()) Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        })
    }

    private fun attachLockCover() {
        if (lockCover != null) return
        lockCover = AppLockHelper.createCover(this) { requestAuthentication() }; root.addView(lockCover, FrameLayout.LayoutParams(-1, -1))
    }

    private fun guardAppLock() {
        if (!AppPreferences.appLockEnabled(this)) { lockCover?.visibility = View.GONE; window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE); attachLockCover()
        if (AppLockSession.authenticated) { lockCover?.visibility = View.GONE; return }
        lockCover?.visibility = View.VISIBLE; lockCover?.bringToFront(); requestAuthentication()
    }

    private fun requestAuthentication() {
        if (lockPromptActive || !AppPreferences.appLockEnabled(this) || AppLockSession.authenticated) return
        if (!AppLockHelper.supported(this)) { Toast.makeText(this, getString(R.string.lock_unavailable), Toast.LENGTH_LONG).show(); return }
        lockPromptActive = true
        AppLockHelper.authenticate(this, { lockPromptActive = false; lockCover?.visibility = View.GONE }, { message ->
            lockPromptActive = false; if (message.isNotBlank()) Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        })
    }

    private fun refreshStatus() {
        if (!::overlayStatus.isInitialized) return
        val granted = android.provider.Settings.canDrawOverlays(this)
        overlayStatus.text = getString(if (granted) R.string.overlay_allowed else R.string.overlay_not_allowed); overlayStatus.setTextColor(if (granted) TEAL else MUTED)
        overlayButton.text = getString(if (granted) R.string.overlay_allowed_button else R.string.overlay_settings_button)
        suppressPopupToggle = true; popupSwitch.isChecked = AppPreferences.popupEnabled(this); suppressPopupToggle = false
        val notificationsEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
        notificationStatus.text = getString(if (notificationsEnabled) R.string.status_enabled else R.string.status_disabled); notificationStatus.setTextColor(if (notificationsEnabled) TEAL else MUTED)
    }

    private fun openOverlayPermission() {
        AppPreferences.setPopupEnabled(this, true); suppressPopupToggle = true; popupSwitch.isChecked = true; suppressPopupToggle = false
        try { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        catch (_: Exception) { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
    }

    private fun previewPopup() {
        AppPreferences.setPopupEnabled(this, true); suppressPopupToggle = true; popupSwitch.isChecked = true; suppressPopupToggle = false
        try { val i = Intent(this, QuickCaptureService::class.java).setAction(QuickCaptureService.ACTION_SHOW_POPUP); if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i) }
        catch (_: Exception) { }
    }

    private fun ensurePopupService() {
        try { val i = Intent(this, QuickCaptureService::class.java); if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i) } catch (_: Exception) { }
    }

    private fun exportBackup() {
        val name = "QuickNote-backup-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.zip"
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/zip").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, name), REQUEST_EXPORT)
    }

    private fun confirmImport() {
        android.app.AlertDialog.Builder(this).setTitle(getString(R.string.backup_import))
            .setMessage(getString(R.string.backup_replace_warning))
            .setPositiveButton(getString(R.string.continue_label)) { _, _ -> startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/zip").addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), REQUEST_IMPORT) }
            .setNegativeButton(getString(R.string.cancel), null).show()
    }

    @Deprecated("Uses SAF activity results for compatibility with the current minSdk")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK || data?.data == null) return
        val uri = data.data!!; val exporting = requestCode == REQUEST_EXPORT
        if (!exporting && requestCode != REQUEST_IMPORT) return
        Toast.makeText(this, getString(R.string.backup_working), Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val count = if (exporting) BackupManager.export(applicationContext, uri) else BackupManager.restore(applicationContext, uri)
                if (!exporting) refreshRestoredData()
                runOnUiThread { Toast.makeText(this, getString(if (exporting) R.string.backup_export_done else R.string.backup_import_done, count), Toast.LENGTH_LONG).show() }
            } catch (error: Exception) {
                runOnUiThread { Toast.makeText(this, error.message ?: getString(R.string.backup_failed), Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun refreshRestoredData() {
        val context = applicationContext
        EntryDb(context).use { db -> db.all().forEach { NotificationHelper.showItem(context, it) } }
        QuickNoteWidgetProvider.refreshAll(context); ReminderScheduler.rescheduleAll(context)
    }

    private fun refreshSavedNotifications() {
        EntryDb(this).use { db -> NotificationHelper.refreshPrivacy(this, db.all()) }
    }

    private fun restartHome() {
        val home = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(home); finish()
    }

    private fun openNotificationSettings() {
        val i = if (Build.VERSION.SDK_INT >= 26) Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
        else Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        startActivity(i)
    }

    private fun openAppSettings() = startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))

    private fun sectionTitle(value: String) = text(value, 11f, MUTED, true).apply { setPadding(dp(2), dp(20), dp(2), dp(8)) }
    private fun card() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = resources.configuration.layoutDirection; setPadding(dp(15), dp(14), dp(15), dp(14)); background = rounded(palette.surface, dp(17), LINE); elevation = dp(1).toFloat() }
    private fun divider() = View(this).apply { setBackgroundColor(LINE) }.also { it.layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(12); bottomMargin = dp(8) } }
    private fun actionButton(title: String, click: () -> Unit) = Button(this).apply { text = title; isAllCaps = false; textSize = 13f; setTextColor(TEAL); background = rounded(palette.soft, dp(12)); setOnClickListener { click() } }
    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply { text = value; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD) }
    private fun rounded(fill: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply { setColor(fill); cornerRadius = radius.toFloat(); if (stroke != null) setStroke(dp(1), stroke) }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object { private const val REQUEST_EXPORT = 501; private const val REQUEST_IMPORT = 502 }
}
