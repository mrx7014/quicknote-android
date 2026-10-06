package com.quicknote.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var db: EntryDb
    private lateinit var listContainer: LinearLayout
    private lateinit var editor: EditText
    private lateinit var noteButton: Button
    private lateinit var taskButton: Button
    private lateinit var voiceButton: Button
    private lateinit var popupStatus: TextView
    private lateinit var popupAction: Button
    private lateinit var countNotes: TextView
    private lateinit var countTasks: TextView
    private lateinit var countVoices: TextView
    private var selectedType = "note"
    private var activeFilter = "all"
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var player: MediaPlayer? = null
    private var recording = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = PAGE
        window.navigationBarColor = PAGE
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        db = EntryDb(this)
        NotificationHelper.createChannels(this)
        requestNotificationPermissionIfNeeded()
        buildScreen()
        refreshEntries()
        handleVoiceIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        updatePopupPanel()
        if (AppPreferences.popupEnabled(this) && Settings.canDrawOverlays(this)) ensurePopupService()
        else stopService(Intent(this, QuickCaptureService::class.java))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleVoiceIntent(intent)
    }

    private fun buildScreen() {
        val scroll = ScrollView(this).apply { clipToPadding = false; setBackgroundColor(PAGE) }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = resources.configuration.layoutDirection
            setPadding(dp(20), dp(16), dp(20), dp(28))
        }
        scroll.addView(page, ViewGroup.LayoutParams(-1, -2))

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val logo = TextView(this).apply {
            text = "Q"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = rounded(TEAL, dp(15))
        }
        top.addView(logo, LinearLayout.LayoutParams(dp(48), dp(48)))
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(11), 0, dp(8), 0) }
        brand.addView(label(getString(R.string.app_name), 19f, INK, true))
        brand.addView(label(getString(R.string.brand_tagline), 12f, MUTED))
        top.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        val settings = label("⚙", 22f, TEAL, true).apply {
            gravity = Gravity.CENTER
            contentDescription = getString(R.string.settings_title)
            background = rounded(Color.WHITE, dp(15), LINE)
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }
        top.addView(settings, LinearLayout.LayoutParams(dp(44), dp(44)))
        page.addView(top)

        val greeting = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(22), 0, dp(14)) }
        greeting.addView(label(getString(R.string.greeting_title), 24f, INK, true))
        greeting.addView(label(getString(R.string.greeting_subtitle), 14f, MUTED).apply { setPadding(0, dp(5), 0, 0) })
        page.addView(greeting)

        val capture = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = resources.configuration.layoutDirection
            setPadding(dp(17), dp(17), dp(17), dp(17))
            background = gradient(intArrayOf(Color.rgb(18, 77, 68), Color.rgb(26, 127, 104)), dp(23))
            elevation = dp(4).toFloat()
        }
        val cardHeading = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val heading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heading.addView(label(getString(R.string.capture_title), 18f, Color.WHITE, true))
        heading.addView(label(getString(R.string.capture_subtitle), 12f, Color.rgb(210, 234, 226)).apply { setPadding(0, dp(3), 0, 0) })
        cardHeading.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        cardHeading.addView(label("● ${getString(R.string.live_badge)}", 10f, Color.rgb(214, 245, 224), true).apply {
            setPadding(dp(9), dp(6), dp(9), dp(6)); background = rounded(Color.argb(38, 255, 255, 255), dp(20))
        })
        capture.addView(cardHeading)

        editor = EditText(this).apply {
            hint = getString(R.string.input_note_hint)
            textSize = 16f
            minLines = 3
            maxLines = 5
            gravity = Gravity.TOP or Gravity.START
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            setTextColor(INK)
            setHintTextColor(Color.rgb(128, 145, 138))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(Color.rgb(249, 251, 248), dp(15))
        }
        capture.addView(editor, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(15) })

        val typeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = resources.configuration.layoutDirection; setPadding(0, dp(12), 0, 0) }
        noteButton = typeButton(getString(R.string.type_note)) { selectType("note") }
        taskButton = typeButton(getString(R.string.type_task)) { selectType("task") }
        voiceButton = typeButton(getString(R.string.type_voice)) { toggleRecording() }
        typeRow.addView(noteButton, weightedParams())
        typeRow.addView(taskButton, weightedParams())
        typeRow.addView(voiceButton, weightedParams(last = true))
        capture.addView(typeRow)

        val saveButton = Button(this).apply {
            text = getString(R.string.save_button)
            isAllCaps = false
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(22, 87, 72))
            background = rounded(Color.rgb(235, 247, 239), dp(14))
            setOnClickListener { saveTextEntry() }
        }
        capture.addView(saveButton, LinearLayout.LayoutParams(-1, dp(49)).apply { topMargin = dp(13) })
        page.addView(capture)

        val popup = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = resources.configuration.layoutDirection
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = rounded(Color.WHITE, dp(17), LINE)
            elevation = dp(2).toFloat()
        }
        popup.addView(label("↗", 20f, TEAL, true).apply {
            gravity = Gravity.CENTER
            background = rounded(Color.rgb(230, 244, 237), dp(13))
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
        val popupCopy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, dp(8), 0) }
        popupCopy.addView(label(getString(R.string.popup_title), 14f, INK, true))
        popupStatus = label("", 11f, MUTED).apply { setPadding(0, dp(3), 0, 0) }
        popupCopy.addView(popupStatus)
        popup.addView(popupCopy, LinearLayout.LayoutParams(0, -2, 1f))
        popupAction = Button(this).apply {
            text = getString(R.string.popup_enable)
            isAllCaps = false
            textSize = 12f
            setTextColor(Color.WHITE)
            background = rounded(TEAL, dp(12))
            setOnClickListener { onPopupAction() }
        }
        popup.addView(popupAction, LinearLayout.LayoutParams(-2, dp(42)))
        page.addView(popup, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(13) })

        page.addView(label(getString(R.string.summary_title), 18f, INK, true).apply { setPadding(0, dp(22), 0, dp(10)) })
        val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = resources.configuration.layoutDirection }
        val stat1 = statCard(getString(R.string.stat_notes), Color.rgb(230, 244, 237))
        val stat2 = statCard(getString(R.string.stat_tasks), Color.rgb(245, 239, 221))
        val stat3 = statCard(getString(R.string.stat_voice), Color.rgb(236, 233, 247))
        countNotes = stat1.second; countTasks = stat2.second; countVoices = stat3.second
        stats.addView(stat1.first, weightedStatParams())
        stats.addView(stat2.first, weightedStatParams())
        stats.addView(stat3.first, weightedStatParams(last = true))
        page.addView(stats)

        val savedTitle = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = resources.configuration.layoutDirection
            setPadding(0, dp(22), 0, dp(10))
        }
        savedTitle.addView(label(getString(R.string.saved_title), 18f, INK, true), LinearLayout.LayoutParams(0, -2, 1f))
        savedTitle.addView(label(getString(R.string.saved_device), 11f, MUTED))
        page.addView(savedTitle)

        val filters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = resources.configuration.layoutDirection }
        val filterLabels = listOf("all" to R.string.filter_all, "note" to R.string.filter_notes, "task" to R.string.filter_tasks, "voice" to R.string.filter_voice)
        filterLabels.forEachIndexed { index, pair ->
            val chip = Button(this).apply {
                text = getString(pair.second)
                isAllCaps = false
                textSize = 11f
                minHeight = dp(38)
                minimumHeight = dp(38)
                setPadding(dp(8), 0, dp(8), 0)
                setOnClickListener { activeFilter = pair.first; refreshEntries() }
            }
            filters.addView(chip, LinearLayout.LayoutParams(0, dp(38), 1f).apply { if (index < 3) marginEnd = dp(6) })
        }
        page.addView(filters)
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = resources.configuration.layoutDirection }
        page.addView(listContainer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        setContentView(scroll)
        selectType("note")
    }

    private fun statCard(title: String, tint: Int): Pair<LinearLayout, TextView> {
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(11), dp(10), dp(11)); background = rounded(tint, dp(15)) }
        val number = label("0", 20f, INK, true)
        card.addView(number)
        card.addView(label(title, 10f, MUTED).apply { setPadding(0, dp(3), 0, 0) })
        return card to number
    }

    private fun weightedStatParams(last: Boolean = false) = LinearLayout.LayoutParams(0, -2, 1f).apply { if (!last) marginEnd = dp(7) }
    private fun typeButton(title: String, click: () -> Unit) = Button(this).apply {
        text = title; isAllCaps = false; textSize = 13f; minHeight = dp(43); minimumHeight = dp(43)
        setPadding(dp(4), 0, dp(4), 0); setOnClickListener { click() }
    }
    private fun weightedParams(last: Boolean = false) = LinearLayout.LayoutParams(0, dp(44), 1f).apply { if (!last) marginEnd = dp(7) }

    private fun selectType(type: String) {
        selectedType = type
        noteButton.background = rounded(if (type == "note") Color.WHITE else Color.argb(35, 255, 255, 255), dp(12))
        taskButton.background = rounded(if (type == "task") Color.WHITE else Color.argb(35, 255, 255, 255), dp(12))
        noteButton.setTextColor(if (type == "note") TEAL else Color.WHITE)
        taskButton.setTextColor(if (type == "task") TEAL else Color.WHITE)
        voiceButton.background = rounded(Color.argb(35, 255, 255, 255), dp(12))
        voiceButton.setTextColor(Color.WHITE)
        editor.hint = getString(if (type == "task") R.string.input_task_hint else R.string.input_note_hint)
    }

    private fun saveTextEntry() {
        val text = editor.text.toString().trim()
        if (text.isEmpty()) { editor.error = getString(R.string.error_empty); editor.requestFocus(); return }
        val id = db.add(selectedType, text)
        db.all().firstOrNull { it.id == id }?.let { NotificationHelper.showItem(this, it) }
        editor.text.clear()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(editor.windowToken, 0)
        Toast.makeText(this, getString(if (selectedType == "task") R.string.saved_task_toast else R.string.saved_note_toast), Toast.LENGTH_SHORT).show()
        refreshEntries()
    }

    private fun toggleRecording() {
        if (recording) stopRecording(true)
        else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording()
        else requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
    }

    private fun startRecording() {
        try {
            val file = File.createTempFile("voice_note_", ".m4a", filesDir)
            val activeRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()
            activeRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            activeRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            activeRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            activeRecorder.setAudioEncodingBitRate(96_000)
            activeRecorder.setAudioSamplingRate(44_100)
            activeRecorder.setOutputFile(file.absolutePath)
            activeRecorder.prepare(); activeRecorder.start()
            recorder = activeRecorder; recordingFile = file; recording = true
            voiceButton.text = getString(R.string.record_stop_save)
            voiceButton.background = rounded(Color.rgb(184, 68, 56), dp(12))
            Toast.makeText(this, getString(R.string.record_started), Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            recorder?.release(); recorder = null; recordingFile?.delete(); recordingFile = null; recording = false
            Toast.makeText(this, getString(R.string.record_failed), Toast.LENGTH_LONG).show()
        }
    }

    private fun stopRecording(save: Boolean) {
        val file = recordingFile
        try { recorder?.stop() } catch (_: Exception) { file?.delete() }
        finally {
            recorder?.release(); recorder = null; recording = false; recordingFile = null
            voiceButton.text = getString(R.string.type_voice)
            voiceButton.background = rounded(Color.argb(35, 255, 255, 255), dp(12))
        }
        if (save && file != null && file.exists() && file.length() > 0) {
            val locale = AppLocale.systemLocale(this)
            val labelText = getString(R.string.item_voice) + " · " + SimpleDateFormat("d MMM, h:mm a", locale).format(Date())
            val id = db.add("voice", labelText, file.absolutePath)
            db.all().firstOrNull { it.id == id }?.let { NotificationHelper.showItem(this, it) }
            Toast.makeText(this, getString(R.string.voice_saved), Toast.LENGTH_SHORT).show()
            refreshEntries()
        }
    }

    private fun refreshEntries() {
        if (!::listContainer.isInitialized) return
        listContainer.removeAllViews()
        val allItems = db.all()
        allItems.forEach { NotificationHelper.showItem(this, it) }
        countNotes.text = allItems.count { it.type == "note" }.toString()
        countTasks.text = allItems.count { it.type == "task" && !it.done }.toString()
        countVoices.text = allItems.count { it.type == "voice" }.toString()
        val items = if (activeFilter == "all") allItems else allItems.filter { it.type == activeFilter }
        if (items.isEmpty()) {
            val empty = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(20), dp(20), dp(20), dp(20)); background = rounded(Color.WHITE, dp(17), LINE) }
            empty.addView(label(getString(if (allItems.isEmpty()) R.string.empty_all_title else R.string.empty_filter_title), 15f, INK, true))
            empty.addView(label(getString(R.string.empty_subtitle), 12f, MUTED).apply { setPadding(0, dp(5), 0, 0) })
            listContainer.addView(empty); return
        }
        items.forEach { entry ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; layoutDirection = resources.configuration.layoutDirection; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(11), dp(10), dp(11)); background = rounded(Color.WHITE, dp(16), LINE)
            }
            val badgeColor = when (entry.type) { "task" -> Color.rgb(249, 243, 225); "voice" -> Color.rgb(237, 234, 248); else -> Color.rgb(230, 244, 237) }
            if (entry.type == "task") {
                val checkbox = CheckBox(this).apply {
                    isChecked = entry.done
                    buttonTintList = android.content.res.ColorStateList.valueOf(TEAL)
                    contentDescription = getString(R.string.task_completed)
                    setOnCheckedChangeListener { _, checked ->
                        db.setDone(entry.id, checked)
                        db.all().firstOrNull { it.id == entry.id }?.let { NotificationHelper.showItem(this@MainActivity, it) }
                        refreshEntries()
                    }
                }
                card.addView(checkbox)
            } else {
                val mark = if (entry.type == "voice") "♫" else "N"
                card.addView(label(mark, 18f, TEAL, true).apply { gravity = Gravity.CENTER; background = rounded(badgeColor, dp(13)) }, LinearLayout.LayoutParams(dp(42), dp(42)))
            }
            val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, dp(7), 0) }
            details.addView(label(entry.content, 14f, if (entry.done) MUTED else INK, true).apply {
                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                if (entry.done) paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
            })
            val typeName = when (entry.type) { "task" -> getString(R.string.item_task); "voice" -> getString(R.string.item_voice); else -> getString(R.string.item_note) }
            val locale = AppLocale.systemLocale(this)
            val stamp = SimpleDateFormat("d MMM, h:mm a", locale).format(Date(entry.createdAt))
            details.addView(label(getString(R.string.item_meta, typeName, stamp), 10f, MUTED).apply { setPadding(0, dp(5), 0, 0) })
            if (entry.type == "voice") details.setOnClickListener { playVoice(entry) }
            card.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(TextView(this).apply {
                text = "×"; textSize = 23f; gravity = Gravity.CENTER; setTextColor(MUTED)
                contentDescription = getString(R.string.delete_item)
                setOnClickListener {
                    val removed = db.delete(entry.id)
                    NotificationHelper.removeItem(this@MainActivity, entry.id)
                    removed?.audioPath?.let { File(it).delete() }
                    refreshEntries()
                }
            }, LinearLayout.LayoutParams(dp(34), dp(42)))
            listContainer.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }

    private fun playVoice(entry: Entry) {
        val path = entry.audioPath ?: return
        try {
            player?.release()
            player = MediaPlayer().apply { setDataSource(path); setOnCompletionListener { it.release(); if (player === it) player = null }; prepare(); start() }
        } catch (_: Exception) { Toast.makeText(this, getString(R.string.voice_play_failed), Toast.LENGTH_SHORT).show() }
    }

    private fun updatePopupPanel() {
        if (!::popupStatus.isInitialized) return
        val permission = Settings.canDrawOverlays(this)
        val enabled = AppPreferences.popupEnabled(this)
        popupStatus.text = when {
            enabled && permission -> getString(R.string.popup_active)
            !permission -> getString(R.string.popup_permission_missing)
            else -> getString(R.string.popup_disabled)
        }
        popupStatus.setTextColor(if (enabled && permission) TEAL else MUTED)
        popupAction.text = if (enabled && permission) getString(R.string.popup_preview) else getString(R.string.popup_enable)
    }

    private fun onPopupAction() {
        AppPreferences.setPopupEnabled(this, true)
        if (!Settings.canDrawOverlays(this)) {
            try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
            catch (_: Exception) { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
            return
        }
        val intent = Intent(this, QuickCaptureService::class.java).setAction(QuickCaptureService.ACTION_SHOW_POPUP)
        try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent) }
        catch (_: Exception) { Toast.makeText(this, getString(R.string.popup_open_failed), Toast.LENGTH_LONG).show() }
        updatePopupPanel()
    }

    private fun ensurePopupService() {
        try {
            val intent = Intent(this, QuickCaptureService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        } catch (_: Exception) { Toast.makeText(this, getString(R.string.popup_open_failed), Toast.LENGTH_LONG).show() }
    }

    private fun handleVoiceIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_START_VOICE_NOTE, false) == true) {
            intent.removeExtra(EXTRA_START_VOICE_NOTE)
            if (::voiceButton.isInitialized) voiceButton.post { toggleRecording() }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_AUDIO && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startRecording()
        if (requestCode == REQ_NOTIFICATIONS && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) refreshEntries()
    }

    override fun onDestroy() {
        if (recording) stopRecording(false)
        player?.release(); player = null
        db.close()
        super.onDestroy()
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun rounded(fill: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply { setColor(fill); cornerRadius = radius.toFloat(); if (stroke != null) setStroke(dp(1), stroke) }
    private fun gradient(colors: IntArray, radius: Int) = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply { cornerRadius = radius.toFloat() }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_START_VOICE_NOTE = "com.quicknote.app.START_VOICE_NOTE"
        private const val PAGE = 0xFFF4F7F3.toInt()
        private const val INK = 0xFF24332E.toInt()
        private const val MUTED = 0xFF788780.toInt()
        private const val TEAL = 0xFF176B5B.toInt()
        private const val LINE = 0xFFE2EAE4.toInt()
        private const val REQ_AUDIO = 401
        private const val REQ_NOTIFICATIONS = 402
    }
}
