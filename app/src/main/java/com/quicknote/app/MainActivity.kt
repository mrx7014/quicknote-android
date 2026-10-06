package com.quicknote.app

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
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
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.fragment.app.FragmentActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : FragmentActivity() {
    private lateinit var db: EntryDb
    private lateinit var root: FrameLayout
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
    private lateinit var searchInput: EditText
    private var lockCover: View? = null
    private var lockPromptActive = false
    private var selectedType = "note"
    private var activeFilter = "all"
    private var searchQuery = ""
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var player: MediaPlayer? = null
    private var recording = false
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
        db = EntryDb(this)
        NotificationHelper.createChannels(this)
        requestNotificationPermissionIfNeeded()
        buildScreen()
        refreshEntries()
        handleCaptureIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        guardAppLock()
        updatePopupPanel()
        refreshEntries()
        if (AppPreferences.popupEnabled(this) && android.provider.Settings.canDrawOverlays(this)) ensurePopupService()
        else stopService(Intent(this, QuickCaptureService::class.java))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent); handleCaptureIntent(intent)
    }

    private fun setSystemColors() {
        window.statusBarColor = PAGE; window.navigationBarColor = PAGE
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (palette.dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    }

    private fun buildScreen() {
        val scroll = ScrollView(this).apply { clipToPadding = false; setBackgroundColor(PAGE) }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = resources.configuration.layoutDirection
            setPadding(dp(20), dp(16), dp(20), dp(28))
        }
        scroll.addView(page, ViewGroup.LayoutParams(-1, -2))
        root = FrameLayout(this).apply { setBackgroundColor(PAGE) }
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = resources.configuration.layoutDirection }
        val logo = TextView(this).apply {
            text = "Q"; textSize = 22f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD); background = rounded(TEAL, dp(15))
        }
        top.addView(logo, LinearLayout.LayoutParams(dp(48), dp(48)))
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(11), 0, dp(8), 0) }
        brand.addView(label(getString(R.string.app_name), 19f, INK, true)); brand.addView(label(getString(R.string.brand_tagline), 12f, MUTED))
        top.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        val settings = label("⚙", 22f, TEAL, true).apply {
            gravity = Gravity.CENTER; contentDescription = getString(R.string.settings_title); background = rounded(palette.surface, dp(15), LINE)
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }
        top.addView(settings, LinearLayout.LayoutParams(dp(44), dp(44))); page.addView(top)

        val greeting = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(22), 0, dp(14)) }
        greeting.addView(label(getString(R.string.greeting_title), 24f, INK, true))
        greeting.addView(label(getString(R.string.greeting_subtitle), 14f, MUTED).apply { setPadding(0, dp(5), 0, 0) })
        page.addView(greeting)

        val capture = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = resources.configuration.layoutDirection; setPadding(dp(17), dp(17), dp(17), dp(17))
            background = gradient(intArrayOf(0xFF124D44.toInt(), 0xFF1A7F68.toInt()), dp(23)); elevation = dp(4).toFloat()
        }
        val cardHeading = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = resources.configuration.layoutDirection }
        val heading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heading.addView(label(getString(R.string.capture_title), 18f, Color.WHITE, true))
        heading.addView(label(getString(R.string.capture_subtitle), 12f, 0xFFD2EAE2.toInt()).apply { setPadding(0, dp(3), 0, 0) })
        cardHeading.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        cardHeading.addView(label("● ${getString(R.string.live_badge)}", 10f, 0xFFD6F5E0.toInt(), true).apply {
            setPadding(dp(9), dp(6), dp(9), dp(6)); background = rounded(Color.argb(38, 255, 255, 255), dp(20))
        })
        capture.addView(cardHeading)

        editor = EditText(this).apply {
            hint = getString(R.string.input_note_hint); textSize = 16f; minLines = 3; maxLines = 5; gravity = Gravity.TOP or Gravity.START
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG; setTextColor(INK); setHintTextColor(palette.hint); setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(palette.field, dp(15))
        }
        capture.addView(editor, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(15) })
        val typeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = resources.configuration.layoutDirection; setPadding(0, dp(12), 0, 0) }
        noteButton = typeButton(getString(R.string.type_note)) { selectType("note") }
        taskButton = typeButton(getString(R.string.type_task)) { selectType("task") }
        voiceButton = typeButton(getString(R.string.type_voice)) { toggleRecording() }
        typeRow.addView(noteButton, weightedParams()); typeRow.addView(taskButton, weightedParams()); typeRow.addView(voiceButton, weightedParams(last = true)); capture.addView(typeRow)
        val saveButton = Button(this).apply {
            text = getString(R.string.save_button); isAllCaps = false; textSize = 15f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (palette.dark) 0xFF17362E.toInt() else 0xFF165748.toInt()); background = rounded(if (palette.dark) 0xFFD5F2E5.toInt() else 0xFFEBF7EF.toInt(), dp(14))
            setOnClickListener { saveTextEntry() }
        }
        capture.addView(saveButton, LinearLayout.LayoutParams(-1, dp(49)).apply { topMargin = dp(13) }); page.addView(capture)

        val popup = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; layoutDirection = resources.configuration.layoutDirection; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(13), dp(14), dp(13)); background = rounded(palette.surface, dp(17), LINE); elevation = dp(2).toFloat()
        }
        popup.addView(label("↗", 20f, TEAL, true).apply { gravity = Gravity.CENTER; background = rounded(palette.panelTint, dp(13)) }, LinearLayout.LayoutParams(dp(42), dp(42)))
        val popupCopy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, dp(8), 0) }
        popupCopy.addView(label(getString(R.string.popup_title), 14f, INK, true)); popupStatus = label("", 11f, MUTED).apply { setPadding(0, dp(3), 0, 0) }; popupCopy.addView(popupStatus)
        popup.addView(popupCopy, LinearLayout.LayoutParams(0, -2, 1f))
        popupAction = Button(this).apply { text = getString(R.string.popup_enable); isAllCaps = false; textSize = 12f; setTextColor(Color.WHITE); background = rounded(TEAL, dp(12)); setOnClickListener { onPopupAction() } }
        popup.addView(popupAction, LinearLayout.LayoutParams(-2, dp(42))); page.addView(popup, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(13) })

        page.addView(label(getString(R.string.summary_title), 18f, INK, true).apply { setPadding(0, dp(22), 0, dp(10)) })
        val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = resources.configuration.layoutDirection }
        val stat1 = statCard(getString(R.string.stat_notes), palette.statNote); val stat2 = statCard(getString(R.string.stat_tasks), palette.statTask); val stat3 = statCard(getString(R.string.stat_voice), palette.statVoice)
        countNotes = stat1.second; countTasks = stat2.second; countVoices = stat3.second
        stats.addView(stat1.first, weightedStatParams()); stats.addView(stat2.first, weightedStatParams()); stats.addView(stat3.first, weightedStatParams(last = true)); page.addView(stats)

        val savedTitle = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = resources.configuration.layoutDirection; setPadding(0, dp(22), 0, dp(10)) }
        savedTitle.addView(label(getString(R.string.saved_title), 18f, INK, true), LinearLayout.LayoutParams(0, -2, 1f))
        savedTitle.addView(label(getString(R.string.saved_device), 11f, MUTED)); page.addView(savedTitle)
        searchInput = EditText(this).apply {
            hint = getString(R.string.search_hint); setSingleLine(true); textSize = 14f; setTextColor(INK); setHintTextColor(palette.hint); setPadding(dp(13), dp(9), dp(13), dp(9)); background = rounded(palette.surface, dp(13), LINE)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { searchQuery = s?.toString().orEmpty().trim(); refreshEntries() }
                override fun afterTextChanged(s: android.text.Editable?) = Unit
            })
        }
        page.addView(searchInput, LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(9) })
        val filterScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val filters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = resources.configuration.layoutDirection }
        listOf("all" to R.string.filter_all, "note" to R.string.filter_notes, "task" to R.string.filter_tasks, "voice" to R.string.filter_voice, "favorites" to R.string.filter_favorites).forEach { pair ->
            filters.addView(filterButton(pair.first, getString(pair.second)), LinearLayout.LayoutParams(-2, dp(38)).apply { marginEnd = dp(6) })
        }
        filterScroll.addView(filters); page.addView(filterScroll)
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = resources.configuration.layoutDirection }
        page.addView(listContainer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        if (AppPreferences.appLockEnabled(this)) attachLockCover()
    }

    private fun attachLockCover() {
        if (lockCover != null || !::root.isInitialized) return
        lockCover = AppLockHelper.createCover(this) { requestAuthentication() }
        root.addView(lockCover, FrameLayout.LayoutParams(-1, -1))
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
            lockPromptActive = false
            if (message.isNotBlank()) Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        })
    }

    private fun statCard(title: String, tint: Int): Pair<LinearLayout, TextView> {
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(11), dp(10), dp(11)); background = rounded(tint, dp(15)) }
        val number = label("0", 20f, INK, true); card.addView(number); card.addView(label(title, 10f, MUTED).apply { setPadding(0, dp(3), 0, 0) })
        return card to number
    }

    private fun weightedStatParams(last: Boolean = false) = LinearLayout.LayoutParams(0, -2, 1f).apply { if (!last) marginEnd = dp(7) }
    private fun typeButton(title: String, click: () -> Unit) = Button(this).apply { text = title; isAllCaps = false; textSize = 13f; minHeight = dp(43); minimumHeight = dp(43); setPadding(dp(4), 0, dp(4), 0); setOnClickListener { click() } }
    private fun weightedParams(last: Boolean = false) = LinearLayout.LayoutParams(0, dp(44), 1f).apply { if (!last) marginEnd = dp(7) }
    private fun filterButton(key: String, title: String) = Button(this).apply {
        tag = key
        text = title; isAllCaps = false; textSize = 11f; minHeight = dp(38); minimumHeight = dp(38); setPadding(dp(12), 0, dp(12), 0)
        background = rounded(if (activeFilter == key) TEAL else palette.surface, dp(18), if (activeFilter == key) null else LINE)
        setTextColor(if (activeFilter == key) Color.WHITE else INK)
        setOnClickListener {
            activeFilter = key
            (parent as? ViewGroup)?.let { row ->
                for (index in 0 until row.childCount) (row.getChildAt(index) as? Button)?.apply {
                    val active = tag == activeFilter
                    background = rounded(if (active) TEAL else palette.surface, dp(18), if (active) null else LINE)
                    setTextColor(if (active) Color.WHITE else INK)
                }
            }
            refreshEntries()
        }
    }

    private fun selectType(type: String) {
        selectedType = type
        noteButton.background = rounded(if (type == "note") palette.surface else Color.argb(35, 255, 255, 255), dp(12))
        taskButton.background = rounded(if (type == "task") palette.surface else Color.argb(35, 255, 255, 255), dp(12))
        noteButton.setTextColor(if (type == "note") TEAL else Color.WHITE); taskButton.setTextColor(if (type == "task") TEAL else Color.WHITE)
        voiceButton.background = rounded(Color.argb(35, 255, 255, 255), dp(12)); voiceButton.setTextColor(Color.WHITE)
        editor.hint = getString(if (type == "task") R.string.input_task_hint else R.string.input_note_hint)
    }

    private fun saveTextEntry() {
        val content = editor.text.toString().trim()
        if (content.isEmpty()) { editor.error = getString(R.string.error_empty); editor.requestFocus(); return }
        val id = db.add(selectedType, content)
        db.get(id)?.let { NotificationHelper.showItem(this, it) }
        editor.text.clear(); (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(editor.windowToken, 0)
        Toast.makeText(this, getString(if (selectedType == "task") R.string.saved_task_toast else R.string.saved_note_toast), Toast.LENGTH_SHORT).show()
        QuickNoteWidgetProvider.refreshAll(this); refreshEntries()
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
            activeRecorder.setAudioSource(MediaRecorder.AudioSource.MIC); activeRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            activeRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC); activeRecorder.setAudioEncodingBitRate(96_000); activeRecorder.setAudioSamplingRate(44_100)
            activeRecorder.setOutputFile(file.absolutePath); activeRecorder.prepare(); activeRecorder.start()
            recorder = activeRecorder; recordingFile = file; recording = true; voiceButton.text = getString(R.string.record_stop_save)
            voiceButton.background = rounded(0xFFB84438.toInt(), dp(12)); Toast.makeText(this, getString(R.string.record_started), Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            recorder?.release(); recorder = null; recordingFile?.delete(); recordingFile = null; recording = false
            Toast.makeText(this, getString(R.string.record_failed), Toast.LENGTH_LONG).show()
        }
    }

    private fun stopRecording(save: Boolean) {
        val file = recordingFile
        try { recorder?.stop() } catch (_: Exception) { file?.delete() }
        finally { recorder?.release(); recorder = null; recording = false; recordingFile = null; voiceButton.text = getString(R.string.type_voice); voiceButton.background = rounded(Color.argb(35, 255, 255, 255), dp(12)) }
        if (save && file != null && file.exists() && file.length() > 0) {
            val labelText = getString(R.string.item_voice) + " · " + SimpleDateFormat("d MMM, h:mm a", AppLocale.systemLocale(this)).format(Date())
            val id = db.add("voice", labelText, file.absolutePath)
            db.get(id)?.let { NotificationHelper.showItem(this, it) }
            Toast.makeText(this, getString(R.string.voice_saved), Toast.LENGTH_SHORT).show(); QuickNoteWidgetProvider.refreshAll(this); refreshEntries()
        }
    }

    private fun refreshEntries() {
        if (!::listContainer.isInitialized) return
        listContainer.removeAllViews()
        val all = db.all()
        countNotes.text = all.count { it.type == "note" }.toString(); countTasks.text = all.count { it.type == "task" && !it.done }.toString(); countVoices.text = all.count { it.type == "voice" }.toString()
        val query = searchQuery.lowercase(Locale.ROOT)
        val items = all.filter { entry ->
            val matchesFilter = when (activeFilter) { "all" -> true; "favorites" -> entry.favorite; else -> entry.type == activeFilter }
            matchesFilter && (query.isBlank() || entry.content.lowercase(Locale.ROOT).contains(query) || entry.tag.lowercase(Locale.ROOT).contains(query))
        }
        if (items.isEmpty()) {
            val empty = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(20), dp(20), dp(20), dp(20)); background = rounded(palette.surface, dp(17), LINE) }
            empty.addView(label(getString(if (all.isEmpty()) R.string.empty_all_title else R.string.empty_filter_title), 15f, INK, true))
            empty.addView(label(getString(R.string.empty_subtitle), 12f, MUTED).apply { setPadding(0, dp(5), 0, 0) }); listContainer.addView(empty); return
        }
        items.forEach { entry ->
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = resources.configuration.layoutDirection; setPadding(dp(12), dp(11), dp(10), dp(10)); background = rounded(palette.surface, dp(16), LINE) }
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = resources.configuration.layoutDirection; gravity = Gravity.CENTER_VERTICAL }
            val badgeColor = when (entry.type) { "task" -> palette.badgeTask; "voice" -> palette.badgeVoice; else -> palette.badgeNote }
            if (entry.type == "task") {
                head.addView(CheckBox(this).apply {
                    isChecked = entry.done; buttonTintList = android.content.res.ColorStateList.valueOf(TEAL); contentDescription = getString(R.string.task_completed)
                    setOnCheckedChangeListener { _, checked ->
                        db.setDone(entry.id, checked); if (checked) ReminderScheduler.cancel(this@MainActivity, entry.id)
                        db.get(entry.id)?.let { NotificationHelper.showItem(this@MainActivity, it) }; refreshEntries()
                    }
                })
            } else head.addView(label(if (entry.type == "voice") "♫" else "N", 18f, TEAL, true).apply { gravity = Gravity.CENTER; background = rounded(badgeColor, dp(13)) }, LinearLayout.LayoutParams(dp(42), dp(42)))
            val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, dp(7), 0) }
            details.addView(label(entry.content, 14f, if (entry.done) MUTED else INK, true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; if (entry.done) paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG })
            val typeName = when (entry.type) { "task" -> getString(R.string.item_task); "voice" -> getString(R.string.item_voice); else -> getString(R.string.item_note) }
            val stamp = SimpleDateFormat("d MMM, h:mm a", AppLocale.systemLocale(this)).format(Date(entry.createdAt))
            details.addView(label(getString(R.string.item_meta, typeName, stamp), 10f, MUTED).apply { setPadding(0, dp(4), 0, 0) })
            if (entry.tag.isNotBlank()) details.addView(label("#${entry.tag}", 10f, TEAL).apply { setPadding(0, dp(3), 0, 0) })
            if (entry.remindAt != null && !entry.done) details.addView(label(getString(R.string.reminder_at, SimpleDateFormat("d MMM, h:mm a", AppLocale.systemLocale(this)).format(Date(entry.remindAt))), 10f, TEAL).apply { setPadding(0, dp(3), 0, 0) })
            if (entry.type == "voice") details.setOnClickListener { playVoice(entry) }
            head.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
            val favorite = TextView(this).apply {
                text = if (entry.favorite) "★" else "☆"; textSize = 22f; gravity = Gravity.CENTER; setTextColor(if (entry.favorite) 0xFFE4A62E.toInt() else MUTED)
                contentDescription = getString(R.string.favorite_toggle); setOnClickListener { db.setFavorite(entry.id, !entry.favorite); refreshEntries() }
            }
            head.addView(favorite, LinearLayout.LayoutParams(dp(38), dp(42))); card.addView(head)
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = resources.configuration.layoutDirection; gravity = Gravity.END or Gravity.CENTER_VERTICAL; setPadding(0, dp(7), 0, 0) }
            actions.addView(smallAction(if (entry.tag.isBlank()) getString(R.string.tag_add) else getString(R.string.tag_edit)) { editTag(entry) }, actionParams())
            if (entry.type == "task" && !entry.done) actions.addView(smallAction(if (entry.remindAt == null) getString(R.string.reminder_add) else getString(R.string.reminder_change)) { chooseReminder(entry) }, actionParams())
            if (entry.type == "voice") actions.addView(smallAction(getString(R.string.transcribe_button)) { transcribe(entry) }, actionParams())
            if (entry.type == "voice") actions.addView(smallAction(getString(R.string.play_button)) { playVoice(entry) }, actionParams())
            actions.addView(smallAction(getString(R.string.delete_item)) { deleteEntry(entry) }, actionParams(last = true))
            card.addView(actions); listContainer.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            NotificationHelper.showItem(this, entry)
        }
    }

    private fun editTag(entry: Entry) {
        val input = EditText(this).apply { setText(entry.tag); hint = getString(R.string.tag_hint); setSingleLine(true) }
        android.app.AlertDialog.Builder(this).setTitle(getString(R.string.tag_edit_title)).setView(input)
            .setPositiveButton(getString(R.string.save_button)) { _, _ -> db.setTag(entry.id, input.text.toString().trim().take(32)); refreshEntries() }
            .setNegativeButton(getString(R.string.cancel), null).show()
    }

    private fun chooseReminder(entry: Entry) {
        val calendar = Calendar.getInstance().apply { timeInMillis = entry.remindAt ?: System.currentTimeMillis(); if (entry.remindAt == null) add(Calendar.HOUR_OF_DAY, 1) }
        DatePickerDialog(this, { _, year, month, day ->
            calendar.set(Calendar.YEAR, year); calendar.set(Calendar.MONTH, month); calendar.set(Calendar.DAY_OF_MONTH, day)
            TimePickerDialog(this, { _, hour, minute ->
                calendar.set(Calendar.HOUR_OF_DAY, hour); calendar.set(Calendar.MINUTE, minute); calendar.set(Calendar.SECOND, 0); calendar.set(Calendar.MILLISECOND, 0)
                if (calendar.timeInMillis <= System.currentTimeMillis()) { Toast.makeText(this, getString(R.string.reminder_must_be_future), Toast.LENGTH_SHORT).show(); return@TimePickerDialog }
                db.setReminder(entry.id, calendar.timeInMillis); ReminderScheduler.schedule(this, entry.id, calendar.timeInMillis)
                db.get(entry.id)?.let { NotificationHelper.showItem(this, it) }; Toast.makeText(this, getString(R.string.reminder_saved), Toast.LENGTH_SHORT).show(); refreshEntries()
            }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), android.text.format.DateFormat.is24HourFormat(this)).show()
        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).apply {
            setButton(DatePickerDialog.BUTTON_NEUTRAL, getString(R.string.reminder_remove)) { _, _ -> db.setReminder(entry.id, null); ReminderScheduler.cancel(this@MainActivity, entry.id); refreshEntries() }
        }.show()
    }

    private fun deleteEntry(entry: Entry) {
        val removed = db.delete(entry.id); ReminderScheduler.cancel(this, entry.id); NotificationHelper.removeItem(this, entry.id); NotificationHelper.cancelReminder(this, entry.id)
        removed?.audioPath?.let { File(it).delete() }; QuickNoteWidgetProvider.refreshAll(this); refreshEntries()
    }

    private fun transcribe(entry: Entry) {
        Toast.makeText(this, getString(R.string.transcription_starting), Toast.LENGTH_SHORT).show()
        VoiceTranscriber.transcribe(this, entry, { text ->
            db.setContent(entry.id, text); db.get(entry.id)?.let { NotificationHelper.showItem(this, it) }; Toast.makeText(this, getString(R.string.transcription_saved), Toast.LENGTH_SHORT).show(); refreshEntries()
        }, { message -> Toast.makeText(this, message, Toast.LENGTH_LONG).show() })
    }

    private fun smallAction(title: String, action: () -> Unit) = TextView(this).apply {
        text = title; textSize = 10f; gravity = Gravity.CENTER; setTextColor(TEAL); setPadding(dp(8), dp(6), dp(8), dp(6)); background = rounded(palette.soft, dp(12)); setOnClickListener { action() }
    }
    private fun actionParams(last: Boolean = false) = LinearLayout.LayoutParams(-2, dp(32)).apply { if (!last) marginEnd = dp(5) }
    private fun Int?.orZero() = this ?: 0

    private fun playVoice(entry: Entry) {
        val path = entry.audioPath ?: return
        try { player?.release(); player = MediaPlayer().apply { setDataSource(path); setOnCompletionListener { it.release(); if (player === it) player = null }; prepare(); start() } }
        catch (_: Exception) { Toast.makeText(this, getString(R.string.voice_play_failed), Toast.LENGTH_SHORT).show() }
    }

    private fun updatePopupPanel() {
        if (!::popupStatus.isInitialized) return
        val permission = android.provider.Settings.canDrawOverlays(this); val enabled = AppPreferences.popupEnabled(this)
        popupStatus.text = when { enabled && permission -> getString(R.string.popup_active); !permission -> getString(R.string.popup_permission_missing); else -> getString(R.string.popup_disabled) }
        popupStatus.setTextColor(if (enabled && permission) TEAL else MUTED); popupAction.text = if (enabled && permission) getString(R.string.popup_preview) else getString(R.string.popup_enable)
    }

    private fun onPopupAction() {
        AppPreferences.setPopupEnabled(this, true)
        if (!android.provider.Settings.canDrawOverlays(this)) {
            try { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
            catch (_: Exception) { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }; return
        }
        val intent = Intent(this, QuickCaptureService::class.java).setAction(QuickCaptureService.ACTION_SHOW_POPUP)
        try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent) }
        catch (_: Exception) { Toast.makeText(this, getString(R.string.popup_open_failed), Toast.LENGTH_LONG).show() }
        updatePopupPanel()
    }

    private fun ensurePopupService() {
        try { val intent = Intent(this, QuickCaptureService::class.java); if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent) }
        catch (_: Exception) { Toast.makeText(this, getString(R.string.popup_open_failed), Toast.LENGTH_LONG).show() }
    }

    private fun handleCaptureIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(EXTRA_START_VOICE_NOTE, false)) {
            intent.removeExtra(EXTRA_START_VOICE_NOTE); if (::voiceButton.isInitialized) voiceButton.post { toggleRecording() }; return
        }
        val type = intent.getStringExtra(EXTRA_CAPTURE_TYPE) ?: return
        intent.removeExtra(EXTRA_CAPTURE_TYPE)
        if (type == "voice") { if (::voiceButton.isInitialized) voiceButton.post { toggleRecording() }; return }
        if (type in setOf("note", "task") && ::editor.isInitialized) {
            selectType(type); editor.post { editor.requestFocus(); (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT) }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_AUDIO && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startRecording()
        if (requestCode == REQ_NOTIFICATIONS && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) refreshEntries()
    }

    override fun onDestroy() {
        if (recording) stopRecording(false); player?.release(); player = null; db.close(); super.onDestroy()
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply { text = value; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD) }
    private fun rounded(fill: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply { setColor(fill); cornerRadius = radius.toFloat(); if (stroke != null) setStroke(dp(1), stroke) }
    private fun gradient(colors: IntArray, radius: Int) = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply { cornerRadius = radius.toFloat() }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_START_VOICE_NOTE = "com.quicknote.app.START_VOICE_NOTE"
        const val EXTRA_CAPTURE_TYPE = "com.quicknote.app.CAPTURE_TYPE"
        private const val REQ_AUDIO = 401
        private const val REQ_NOTIFICATIONS = 402
    }
}
