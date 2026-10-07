package com.quicknote.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class SettingsActivity : FragmentActivity() {
    private lateinit var root: FrameLayout
    private lateinit var scroll: ScrollView
    private lateinit var page: LinearLayout
    private lateinit var popupSwitch: Switch
    private lateinit var popupStatus: TextView
    private lateinit var popupButton: Button
    private lateinit var notificationStatus: TextView
    private lateinit var lockSwitch: Switch
    private lateinit var lockTimeoutGroup: RadioGroup
    private lateinit var appContent: View
    private var lockCover: View? = null
    private var lockPromptActive = false
    private var suppressPopupToggle = false
    private var pendingOverlayEnable = false
    private var settingsFingerprint = ""
    private val palette get() = AppPalette.from(this)
    private val executor = Executors.newSingleThreadExecutor()

    private val exportDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) performBackupExport(uri)
    }
    private val importDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) confirmRestore(uri)
    }

    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(AppLocale.wrap(newBase)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsFingerprint = settingsFingerprint()
        applyWindowStyle()
        buildScreen()
        applySecurityFlags()
    }

    override fun onResume() {
        super.onResume()
        if (::root.isInitialized && settingsFingerprint != settingsFingerprint()) {
            settingsFingerprint = settingsFingerprint()
            AppLockSession.preserveAcrossConfigurationChange()
            recreate()
            return
        }
        applyWindowStyle()
        guardAppLock()
        val permission = android.provider.Settings.canDrawOverlays(this)
        if (pendingOverlayEnable) {
            pendingOverlayEnable = false
            if (permission) {
                AppPreferences.setPopupEnabled(this, true)
                setPopupChecked(true)
                ensurePopupService()
            } else {
                setPopupChecked(false)
                Toast.makeText(this, getString(R.string.overlay_permission_cancelled), Toast.LENGTH_SHORT).show()
            }
        }
        if (AppPreferences.popupEnabled(this) && !permission) {
            AppPreferences.setPopupEnabled(this, false)
            stopService(Intent(this, QuickCaptureService::class.java))
        }
        refreshStatus()
    }

    private fun applyWindowStyle() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        val bars = WindowInsetsControllerCompat(window, window.decorView)
        bars.isAppearanceLightStatusBars = !palette.dark
        bars.isAppearanceLightNavigationBars = !palette.dark
    }

    private fun buildScreen() {
        scroll = ScrollView(this).apply { clipToPadding = false; setBackgroundColor(palette.page) }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, maxOf(safe.bottom, ime.bottom))
            insets
        }
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(13), dp(18), dp(28))
            setBackgroundColor(palette.page)
        }
        scroll.addView(page, ViewGroup.LayoutParams(-1, -2))
        root = FrameLayout(this).apply { setBackgroundColor(palette.page); addView(scroll, FrameLayout.LayoutParams(-1, -1)) }
        setContentView(root)
        appContent = scroll

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val back = ImageButton(this).apply {
            setImageResource(R.drawable.ic_back)
            setColorFilter(palette.ink)
            contentDescription = getString(R.string.settings_back)
            minimumWidth = dp(48); minimumHeight = dp(48)
            background = rounded(palette.surface, dp(14), palette.line)
            setOnClickListener { finish() }
            if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) rotation = 180f
        }
        header.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        val heading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPaddingRelative(dp(12), 0, 0, 0) }
        heading.addView(text(getString(R.string.settings_title), 22f, palette.ink, true))
        heading.addView(text(getString(R.string.settings_subtitle), 13f, palette.muted).apply { setPadding(0, dp(2), 0, 0) })
        header.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        page.addView(header)

        page.addView(sectionTitle(getString(R.string.language_section)))
        val languageCard = card()
        languageCard.addView(text(getString(R.string.language_description), 13f, palette.muted).apply { setPadding(0, 0, 0, dp(8)) })
        val languages = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        addRadio(languages, 101, getString(R.string.language_system), AppPreferences.selectedLanguage(this) == "system")
        addRadio(languages, 102, getString(R.string.language_english), AppPreferences.selectedLanguage(this) == "en")
        addRadio(languages, 103, getString(R.string.language_arabic), AppPreferences.selectedLanguage(this) == "ar")
        languages.setOnCheckedChangeListener { _, checked ->
            val code = when (checked) { 101 -> "system"; 102 -> "en"; 103 -> "ar"; else -> null }
            if (code != null && code != AppPreferences.selectedLanguage(this)) changeLanguage(code)
        }
        languageCard.addView(languages)
        page.addView(languageCard)

        page.addView(sectionTitle(getString(R.string.popup_section)))
        val popupCard = card()
        val popupRow = settingRow(getString(R.string.popup_settings_title), getString(R.string.popup_settings_description),
            Switch(this).apply {
                text = getString(R.string.popup_switch)
                isChecked = AppPreferences.popupEnabled(this@SettingsActivity) && android.provider.Settings.canDrawOverlays(this@SettingsActivity)
                setOnCheckedChangeListener { _, enabled -> if (!suppressPopupToggle) onPopupSwitchChanged(enabled) }
            })
        popupSwitch = (popupRow.tag as Switch)
        popupCard.addView(popupRow)
        popupCard.addView(divider())
        popupStatus = text("", 13f, palette.muted).apply { setPadding(0, dp(9), 0, dp(5)) }
        popupCard.addView(popupStatus)
        popupCard.addView(text(getString(R.string.popup_cost_disclosure), 12f, palette.muted).apply { setPadding(0, dp(2), 0, dp(10)) })
        popupButton = actionButton(getString(R.string.overlay_settings_button)) { onPopupButton() }
        popupCard.addView(popupButton, LinearLayout.LayoutParams(-1, dp(48)))
        page.addView(popupCard)

        page.addView(sectionTitle(getString(R.string.appearance_privacy_section)))
        val appearance = card()
        appearance.addView(text(getString(R.string.dark_mode_title), 15f, palette.ink, true))
        appearance.addView(text(getString(R.string.dark_mode_description), 12f, palette.muted).apply { setPadding(0, dp(3), 0, dp(8)) })
        val themes = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val currentTheme = AppPreferences.themeMode(this)
        addRadio(themes, 201, getString(R.string.theme_system), currentTheme == "system")
        addRadio(themes, 202, getString(R.string.theme_light), currentTheme == "light")
        addRadio(themes, 203, getString(R.string.theme_dark), currentTheme == "dark")
        themes.setOnCheckedChangeListener { _, checked ->
            val value = when (checked) { 201 -> "system"; 202 -> "light"; 203 -> "dark"; else -> null }
            if (value != null && value != AppPreferences.themeMode(this)) {
                AppPreferences.setThemeMode(this, value)
                QuickNoteWidgetProvider.refreshAll(this)
                recreateForPreferenceChange()
            }
        }
        appearance.addView(themes)
        appearance.addView(divider())
        appearance.addView(switchRow(getString(R.string.hide_notifications_title), getString(R.string.hide_notifications_description), AppPreferences.hideNotificationContent(this)) { enabled ->
            AppPreferences.setHideNotificationContent(this, enabled)
            refreshSavedNotifications()
        })
        appearance.addView(divider())
        appearance.addView(switchRow(getString(R.string.protect_screen_title), getString(R.string.protect_screen_description), AppPreferences.protectScreenContent(this)) { enabled ->
            AppPreferences.setProtectScreenContent(this, enabled)
            applySecurityFlags()
        })
        appearance.addView(divider())
        lockSwitch = Switch(this).apply {
            text = getString(R.string.app_lock_title)
            isChecked = AppPreferences.appLockEnabled(this@SettingsActivity)
            minHeight = dp(48)
            setOnCheckedChangeListener { _, enabled -> onLockSwitchChanged(enabled) }
        }
        appearance.addView(settingRow(getString(R.string.app_lock_title), getString(R.string.app_lock_description), lockSwitch))
        lockTimeoutGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        appearance.addView(text(getString(R.string.lock_timeout_title), 14f, palette.ink, true).apply { setPadding(0, dp(10), 0, dp(3)) })
        appearance.addView(text(getString(R.string.lock_timeout_description), 12f, palette.muted).apply { setPadding(0, 0, 0, dp(5)) })
        listOf(
            "immediate" to R.string.lock_timeout_immediate,
            "30s" to R.string.lock_timeout_30s,
            "1m" to R.string.lock_timeout_1m,
            "5m" to R.string.lock_timeout_5m
        ).forEachIndexed { index, (mode, label) -> addRadio(lockTimeoutGroup, 301 + index, getString(label), AppPreferences.lockTimeoutMode(this) == mode) }
        lockTimeoutGroup.setOnCheckedChangeListener { _, checked ->
            val selected = when (checked) { 301 -> "immediate"; 302 -> "30s"; 303 -> "1m"; 304 -> "5m"; else -> null }
            if (selected != null) AppPreferences.setLockTimeoutMode(this, selected)
        }
        appearance.addView(lockTimeoutGroup)
        page.addView(appearance)
        updateLockTimeoutVisibility()

        page.addView(sectionTitle(getString(R.string.backup_section)))
        val backup = card()
        backup.addView(text(getString(R.string.backup_description), 13f, palette.muted))
        backup.addView(text(getString(R.string.backup_plaintext_notice), 12f, palette.warning).apply { setPadding(0, dp(8), 0, dp(3)) })
        val backupRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        backupRow.addView(actionButton(getString(R.string.backup_export)) { chooseExportLocation() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6); topMargin = dp(10) })
        backupRow.addView(actionButton(getString(R.string.backup_import)) { chooseRestoreFile() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(6); topMargin = dp(10) })
        backup.addView(backupRow)
        page.addView(backup)

        page.addView(sectionTitle(getString(R.string.quick_access_section)))
        val quickAccess = card()
        quickAccess.addView(text(getString(R.string.quick_access_description), 13f, palette.muted))
        page.addView(quickAccess)

        page.addView(sectionTitle(getString(R.string.notification_settings_title)))
        val notifications = card()
        notifications.addView(text(getString(R.string.notification_settings_description), 13f, palette.muted))
        notificationStatus = text("", 12f, palette.teal, true).apply { setPadding(0, dp(8), 0, dp(8)) }
        notifications.addView(notificationStatus)
        notifications.addView(actionButton(getString(R.string.notification_settings_button)) { openNotificationSettings() }, LinearLayout.LayoutParams(-1, dp(48)))
        page.addView(notifications)

        page.addView(sectionTitle(getString(R.string.microphone_settings_title)))
        val microphone = card()
        microphone.addView(text(getString(R.string.microphone_settings_description), 13f, palette.muted))
        microphone.addView(actionButton(getString(R.string.microphone_settings_button)) { openAppSettings() }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })
        page.addView(microphone)

        page.addView(sectionTitle(getString(R.string.about_section)))
        val about = card().apply { background = rounded(palette.panelTint, dp(17)) }
        about.addView(text(getString(R.string.privacy_title), 15f, palette.ink, true))
        about.addView(text(getString(R.string.privacy_description), 12f, palette.muted).apply { setPadding(0, dp(5), 0, 0) })
        about.addView(text(getString(R.string.security_limitations), 12f, palette.muted).apply { setPadding(0, dp(8), 0, 0) })
        page.addView(about)
        page.addView(text(getString(R.string.version_label, BuildConfig.VERSION_NAME), 12f, palette.muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(3))
        })

        if (AppPreferences.appLockEnabled(this)) attachLockCover()
    }

    private fun onPopupSwitchChanged(enabled: Boolean) {
        if (!enabled) {
            AppPreferences.setPopupEnabled(this, false)
            stopService(Intent(this, QuickCaptureService::class.java))
            refreshStatus()
            return
        }
        setPopupChecked(false)
        val prefs = AppPreferences.preferences(this)
        if (!prefs.getBoolean(KEY_POPUP_DISCLOSURE_ACCEPTED, false)) {
            android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.enable_popup_dialog_title))
                .setMessage(getString(R.string.popup_cost_disclosure) + "\n\n" + getString(R.string.enable_popup_dialog_message))
                .setPositiveButton(getString(R.string.continue_label)) { _, _ ->
                    prefs.edit().putBoolean(KEY_POPUP_DISCLOSURE_ACCEPTED, true).apply()
                    continuePopupEnable()
                }
                .setNegativeButton(getString(R.string.later), null)
                .show()
        } else continuePopupEnable()
    }

    private fun continuePopupEnable() {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            pendingOverlayEnable = true
            openOverlayPermission()
        } else {
            AppPreferences.setPopupEnabled(this, true)
            setPopupChecked(true)
            ensurePopupService()
            refreshStatus()
        }
    }

    private fun onPopupButton() {
        if (android.provider.Settings.canDrawOverlays(this)) previewPopup() else {
            showPopupDisclosure()
        }
    }

    private fun showPopupDisclosure() {
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.enable_popup_dialog_title))
            .setMessage(getString(R.string.popup_cost_disclosure) + "\n\n" + getString(R.string.enable_popup_dialog_message))
            .setPositiveButton(getString(R.string.continue_label)) { _, _ ->
                AppPreferences.preferences(this).edit().putBoolean(KEY_POPUP_DISCLOSURE_ACCEPTED, true).apply()
                pendingOverlayEnable = true
                openOverlayPermission()
            }
            .setNegativeButton(getString(R.string.later), null)
            .show()
    }

    private fun openOverlayPermission() {
        AppLockSession.markExternalIntentLaunch()
        try {
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }
    }

    private fun previewPopup() {
        AppPreferences.setPopupEnabled(this, true)
        setPopupChecked(true)
        ensurePopupService()
        val intent = Intent(this, QuickCaptureService::class.java).setAction(QuickCaptureService.ACTION_SHOW_POPUP)
        try { if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent) }
        catch (_: Exception) { Toast.makeText(this, getString(R.string.popup_open_failed), Toast.LENGTH_LONG).show() }
    }

    private fun ensurePopupService() {
        try {
            val intent = Intent(this, QuickCaptureService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        } catch (_: Exception) { Toast.makeText(this, getString(R.string.popup_open_failed), Toast.LENGTH_LONG).show() }
    }

    private fun refreshStatus() {
        if (!::popupStatus.isInitialized) return
        val granted = android.provider.Settings.canDrawOverlays(this)
        popupStatus.text = getString(when {
            AppPreferences.popupEnabled(this) && granted -> R.string.popup_active
            !granted -> R.string.overlay_not_allowed
            else -> R.string.popup_disabled
        })
        popupStatus.setTextColor(if (granted && AppPreferences.popupEnabled(this)) palette.teal else palette.muted)
        popupButton.text = getString(if (granted) R.string.overlay_allowed_button else R.string.overlay_settings_button)
        setPopupChecked(AppPreferences.popupEnabled(this) && granted)
        val allowed = androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()
        notificationStatus.text = getString(if (allowed) R.string.status_enabled else R.string.status_disabled)
        notificationStatus.setTextColor(if (allowed) palette.teal else palette.muted)
        updateLockTimeoutVisibility()
    }

    private fun setPopupChecked(value: Boolean) {
        if (!::popupSwitch.isInitialized) return
        suppressPopupToggle = true
        popupSwitch.isChecked = value
        suppressPopupToggle = false
    }

    private fun changeLanguage(code: String) {
        stopService(Intent(this, QuickCaptureService::class.java))
        AppPreferences.setFrameworkLanguage(this, code)
        QuickNoteWidgetProvider.refreshAll(this)
        recreateForPreferenceChange()
    }

    private fun onLockSwitchChanged(enabled: Boolean) {
        if (!enabled) {
            AppPreferences.setAppLockEnabled(this, false)
            AppLockSession.authenticated = true
            lockCover?.visibility = View.GONE
            applySecurityFlags()
            updateLockTimeoutVisibility()
            return
        }
        if (!AppLockHelper.supported(this)) {
            suppressLockSwitch(false)
            Toast.makeText(this, getString(R.string.lock_unavailable), Toast.LENGTH_LONG).show()
            return
        }
        lockSwitch.isEnabled = false
        AppLockHelper.authenticate(this, {
            AppPreferences.setAppLockEnabled(this, true)
            AppLockSession.authenticated = true
            lockSwitch.isEnabled = true
            applySecurityFlags()
            updateLockTimeoutVisibility()
        }, { message ->
            AppPreferences.setAppLockEnabled(this, false)
            suppressLockSwitch(false)
            lockSwitch.isEnabled = true
            if (message.isNotBlank()) Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        })
    }

    private fun suppressLockSwitch(checked: Boolean) {
        lockSwitch.setOnCheckedChangeListener(null)
        lockSwitch.isChecked = checked
        lockSwitch.setOnCheckedChangeListener { _, enabled -> onLockSwitchChanged(enabled) }
    }

    private fun updateLockTimeoutVisibility() {
        if (::lockTimeoutGroup.isInitialized) lockTimeoutGroup.visibility = if (AppPreferences.appLockEnabled(this)) View.VISIBLE else View.GONE
    }

    private fun applySecurityFlags() {
        if (AppPreferences.appLockEnabled(this) || AppPreferences.protectScreenContent(this)) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun attachLockCover() {
        if (lockCover != null || !::root.isInitialized) return
        lockCover = AppLockHelper.createCover(this) { requestAuthentication() }
        root.addView(lockCover, FrameLayout.LayoutParams(-1, -1))
    }

    private fun setProtectedContentVisible(visible: Boolean) {
        appContent.importantForAccessibility = if (visible) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        lockCover?.visibility = if (visible) View.GONE else View.VISIBLE
    }

    private fun guardAppLock() {
        if (!AppPreferences.appLockEnabled(this)) {
            lockCover?.visibility = View.GONE
            AppLockSession.authenticated = true
            applySecurityFlags()
            setProtectedContentVisible(true)
            return
        }
        if (!AppLockHelper.supported(this)) {
            AppPreferences.setAppLockEnabled(this, false)
            AppLockSession.authenticated = true
            Toast.makeText(this, getString(R.string.lock_disabled_unsupported), Toast.LENGTH_LONG).show()
            applySecurityFlags()
            setProtectedContentVisible(true)
            return
        }
        attachLockCover()
        applySecurityFlags()
        if (AppLockSession.shouldLock(this)) {
            setProtectedContentVisible(false)
            requestAuthentication()
        } else setProtectedContentVisible(true)
    }

    private fun requestAuthentication() {
        if (lockPromptActive || !AppPreferences.appLockEnabled(this) || AppLockSession.authenticated) return
        if (!AppLockHelper.supported(this)) { guardAppLock(); return }
        lockPromptActive = true
        AppLockHelper.authenticate(this, {
            lockPromptActive = false
            setProtectedContentVisible(true)
        }, { message ->
            lockPromptActive = false
            if (message.isNotBlank()) Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        })
    }

    private fun chooseExportLocation() {
        AppLockSession.markExternalIntentLaunch()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        exportDocument.launch("QuickNote-backup-$stamp.zip")
    }

    private fun chooseRestoreFile() {
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.backup_import))
            .setMessage(getString(R.string.backup_replace_warning) + "\n\n" + getString(R.string.backup_plaintext_notice))
            .setPositiveButton(getString(R.string.continue_label)) { _, _ ->
                AppLockSession.markExternalIntentLaunch()
                importDocument.launch(arrayOf("application/zip", "application/octet-stream"))
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun confirmRestore(uri: Uri) {
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.backup_import))
            .setMessage(getString(R.string.backup_replace_warning))
            .setPositiveButton(getString(R.string.backup_import)) { _, _ -> performBackupRestore(uri) }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun performBackupExport(uri: Uri) {
        Toast.makeText(this, getString(R.string.backup_working), Toast.LENGTH_SHORT).show()
        executor.execute {
            try {
                val count = BackupManager.export(applicationContext, uri)
                runOnUiThread {
                    Toast.makeText(this, resources.getQuantityString(R.plurals.backup_export_done, count, count), Toast.LENGTH_LONG).show()
                }
            } catch (error: BackupException) { showBackupError(error) }
            catch (error: Exception) { android.util.Log.e("QuickNoteBackup", "Export failed", error); showBackupError(null) }
        }
    }

    private fun performBackupRestore(uri: Uri) {
        Toast.makeText(this, getString(R.string.backup_working), Toast.LENGTH_SHORT).show()
        executor.execute {
            try {
                val count = BackupManager.restore(applicationContext, uri)
                runOnUiThread {
                    Toast.makeText(this, resources.getQuantityString(R.plurals.backup_import_done, count, count), Toast.LENGTH_LONG).show()
                }
            } catch (error: BackupException) { showBackupError(error) }
            catch (error: Exception) { android.util.Log.e("QuickNoteBackup", "Restore failed", error); showBackupError(null) }
        }
    }

    private fun showBackupError(error: BackupException?) {
        runOnUiThread { Toast.makeText(this, getString(error?.messageRes ?: R.string.backup_failed), Toast.LENGTH_LONG).show() }
    }

    private fun refreshSavedNotifications() {
        executor.execute {
            EntryDb(applicationContext).use { db -> NotificationHelper.refreshPrivacy(applicationContext, db.all()) }
        }
    }

    private fun openNotificationSettings() {
        AppLockSession.markExternalIntentLaunch()
        val intent = if (Build.VERSION.SDK_INT >= 26) Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
        else Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        startActivity(intent)
    }

    private fun openAppSettings() {
        AppLockSession.markExternalIntentLaunch()
        startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun settingsFingerprint() = "${AppPreferences.selectedLanguage(this)}:${AppPreferences.themeMode(this)}"
    private fun recreateForPreferenceChange() {
        settingsFingerprint = settingsFingerprint()
        AppLockSession.preserveAcrossConfigurationChange()
        recreate()
    }

    private fun addRadio(group: RadioGroup, id: Int, label: String, checked: Boolean) {
        val radio = RadioButton(this).apply {
            this.id = id
            text = label
            textSize = 14f
            setTextColor(palette.ink)
            minHeight = dp(48)
            buttonTintList = android.content.res.ColorStateList.valueOf(palette.teal)
            isChecked = checked
            setPaddingRelative(dp(7), 0, dp(6), 0)
            contentDescription = label
        }
        group.addView(radio, RadioGroup.LayoutParams(-1, dp(48)))
    }

    private fun switchRow(title: String, description: String, checked: Boolean, changed: (Boolean) -> Unit): View {
        val toggle = Switch(this).apply {
            isChecked = checked
            minHeight = dp(48)
            setOnCheckedChangeListener { _, value -> changed(value) }
        }
        return settingRow(title, description, toggle)
    }

    private fun settingRow(title: String, description: String, toggle: Switch): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPaddingRelative(0, dp(4), dp(8), dp(4)) }
        copy.addView(text(title, 14f, palette.ink, true))
        copy.addView(text(description, 12f, palette.muted).apply { setPadding(0, dp(4), 0, 0) })
        row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(toggle, LinearLayout.LayoutParams(-2, dp(48)))
        row.tag = toggle
        return row
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(13), dp(14), dp(13))
        background = rounded(palette.surface, dp(18), palette.line)
        elevation = dp(1).toFloat()
    }

    private fun sectionTitle(value: String) = text(value, 12f, palette.muted, true).apply {
        setPadding(dp(3), dp(20), dp(3), dp(8))
        ViewCompat.setAccessibilityHeading(this, true)
    }

    private fun divider() = View(this).apply { setBackgroundColor(palette.line) }.also {
        it.layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(10); bottomMargin = dp(8) }
    }

    private fun actionButton(title: String, click: () -> Unit) = Button(this).apply {
        text = title
        isAllCaps = false
        textSize = 13f
        minHeight = dp(48)
        setTextColor(palette.teal)
        background = rounded(palette.soft, dp(12))
        setOnClickListener { click() }
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun rounded(fill: Int, radius: Int, stroke: Int? = null): Drawable {
        val shape = GradientDrawable().apply { setColor(fill); cornerRadius = radius.toFloat(); if (stroke != null) setStroke(dp(1), stroke) }
        return RippleDrawable(android.content.res.ColorStateList.valueOf(ColorUtils.setAlphaComponent(palette.teal, 28)), shape, null)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    companion object { private const val KEY_POPUP_DISCLOSURE_ACCEPTED = "popup_disclosure_accepted" }
}
