package com.quicknote.app

import android.Manifest
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : FragmentActivity() {
    private lateinit var db: EntryDb
    private lateinit var root: FrameLayout
    private lateinit var scroll: ScrollView
    private lateinit var contentPage: LinearLayout
    private lateinit var listContainer: LinearLayout
    private lateinit var editor: EditText
    private lateinit var noteButton: Button
    private lateinit var taskButton: Button
    private lateinit var voiceButton: Button
    private lateinit var saveButton: Button
    private lateinit var popupCard: View
    private lateinit var popupStatus: TextView
    private lateinit var popupAction: Button
    private lateinit var countNotes: TextView
    private lateinit var countTasks: TextView
    private lateinit var countVoices: TextView
    private lateinit var searchInput: EditText
    private lateinit var clearSearch: ImageButton
    private lateinit var lockCover: View
    private var lockPromptActive = false
    private var suppressSearchWatcher = false
    private var selectedType = "note"
    private var activeFilter = "all"
    private var searchQuery = ""
    private var sortMode = "updated"
    private var entriesCache: List<Entry> = emptyList()
    private val filterButtons = mutableMapOf<String, Button>()
    private val palette by lazy(LazyThreadSafetyMode.NONE) { AppPalette.from(this) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val debounceSearch = Runnable { renderEntries() }
    private var audioRecorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var recordingStartedAt = 0L
    private var recording = false
    private var player: MediaPlayer? = null
    private var playingEntryId: Long? = null
    private var transcriptionDialog: AlertDialog? = null
    private val recordTicker = object : Runnable {
        override fun run() {
            if (!recording || !::voiceButton.isInitialized) return
            val elapsed = (System.currentTimeMillis() - recordingStartedAt).coerceAtLeast(0L)
            if (elapsed >= MAX_RECORDING_MS) {
                stopRecording(save = true)
                Toast.makeText(this@MainActivity, getString(R.string.recording_limit_reached), Toast.LENGTH_LONG).show()
                return
            }
            voiceButton.text = getString(R.string.recording_elapsed, DateUtils.formatElapsedTime(elapsed / 1000))
            mainHandler.postDelayed(this, 1000L)
        }
    }
    private var pendingReminderEntry: Entry? = null
    private var pendingEntryOpenId: Long? = null
    private var undoEntry: Entry? = null
    private var undoView: View? = null
    private val undoHandler = Handler(Looper.getMainLooper())
    private var undoRunnable: Runnable? = null
    private var settingsFingerprint = ""
    private var remindersInitialized = false
    private var deferredDraftState: Bundle? = null
    private var draftRestored = false
    private val persistDraft = Runnable {
        if (!::editor.isInitialized) return@Runnable
        if (AppPreferences.appLockEnabled(this@MainActivity) && !AppLockSession.authenticated) return@Runnable
        getPreferences(MODE_PRIVATE).edit()
            .putString(PREF_DRAFT_TEXT, editor.text.toString().take(MAX_ENTRY_TEXT))
            .putString(PREF_DRAFT_TYPE, selectedType)
            .putString(PREF_SEARCH_QUERY, if (::searchInput.isInitialized) searchInput.text.toString().take(MAX_SEARCH_LENGTH) else "")
            .apply()
    }

    private val audioPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else explainDeniedAudioPermission()
    }
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val item = pendingReminderEntry
        pendingReminderEntry = null
        if (granted) ioExecutor.execute { ReminderScheduler.rescheduleAll(applicationContext) }
        if (granted && item != null) chooseReminderPreset(item)
        else if (!granted && item != null) explainNotificationsBlocked(item)
    }

    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(AppLocale.wrap(newBase)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsFingerprint = currentSettingsFingerprint()
        sortMode = AppPreferences.sortMode(this)
        selectedType = savedInstanceState?.getString(STATE_TYPE) ?: "note"
        activeFilter = savedInstanceState?.getString(STATE_FILTER) ?: "all"
        searchQuery = if (AppPreferences.appLockEnabled(this)) "" else savedInstanceState?.getString(STATE_QUERY).orEmpty()
        pendingEntryOpenId = intent.getLongExtra(EXTRA_OPEN_ENTRY_ID, -1L).takeIf { it > 0L }
        applyWindowStyle()
        db = EntryDb(this)
        NotificationHelper.createChannels(this)
        buildScreen()
        deferredDraftState = savedInstanceState
        if (!AppPreferences.appLockEnabled(this)) restoreDeferredDraftIfAllowed()
        applySecurityFlags()
        if (!AppPreferences.appLockEnabled(this)) loadEntries()
        handleIncomingIntent(intent)
        sweepOrphanAudioFiles()
    }

    override fun onResume() {
        super.onResume()
        if (::contentPage.isInitialized && settingsFingerprint != currentSettingsFingerprint()) {
            settingsFingerprint = currentSettingsFingerprint()
            AppLockSession.preserveAcrossConfigurationChange()
            recreate()
            return
        }
        applyWindowStyle()
        guardAppLock()
        restoreDeferredDraftIfAllowed()
        updatePopupPanel()
        if (AppPreferences.popupEnabled(this) && android.provider.Settings.canDrawOverlays(this)) ensurePopupService()
        else stopService(Intent(this, QuickCaptureService::class.java))
        if (!AppPreferences.appLockEnabled(this) || AppLockSession.authenticated) {
            if (!remindersInitialized) {
                remindersInitialized = true
                ioExecutor.execute { ReminderScheduler.rescheduleAll(applicationContext) }
            }
            loadEntries()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (!AppPreferences.appLockEnabled(this) || AppLockSession.authenticated) {
            if (::editor.isInitialized) outState.putString(STATE_DRAFT, editor.text.toString())
            if (::searchInput.isInitialized) outState.putString(STATE_QUERY, searchInput.text.toString())
        }
        outState.putString(STATE_TYPE, selectedType)
        outState.putString(STATE_FILTER, activeFilter)
        outState.putString(STATE_SORT, sortMode)
        super.onSaveInstanceState(outState)
    }

    private fun applyWindowStyle() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = !palette.dark
        controller.isAppearanceLightNavigationBars = !palette.dark
    }

    private fun applyInsets(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            target.setPadding(safe.left, safe.top, safe.right, maxOf(safe.bottom, ime.bottom))
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }

    private fun buildScreen() {
        scroll = ScrollView(this).apply { clipToPadding = false; setBackgroundColor(palette.page) }
        applyInsets(scroll)
        contentPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(22))
            setBackgroundColor(palette.page)
        }
        scroll.addView(contentPage, ViewGroup.LayoutParams(-1, -2))
        root = FrameLayout(this).apply { setBackgroundColor(palette.page); addView(scroll, FrameLayout.LayoutParams(-1, -1)) }
        setContentView(root)

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.ic_quicknote)
            contentDescription = getString(R.string.app_name)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        header.addView(logo, LinearLayout.LayoutParams(dp(46), dp(46)))
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPaddingRelative(dp(10), 0, dp(6), 0) }
        brand.addView(label(getString(R.string.app_name), 19f, palette.ink, true))
        brand.addView(label(getString(R.string.brand_tagline), 12f, palette.muted))
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        val settings = imageButton(R.drawable.ic_settings, R.string.settings_title).apply {
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }
        header.addView(settings, LinearLayout.LayoutParams(dp(48), dp(48)))
        contentPage.addView(header)

        contentPage.addView(label(getString(R.string.greeting_title), 21f, palette.ink, true).apply {
            setPadding(0, dp(15), 0, dp(10))
        })

        val capture = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(15), dp(15), dp(15))
            background = rounded(0xFF124D44.toInt(), dp(22), null)
            elevation = dp(2).toFloat()
        }
        capture.addView(label(getString(R.string.capture_title), 18f, Color.WHITE, true))
        capture.addView(label(getString(R.string.capture_subtitle), 13f, Color.WHITE).apply { setPadding(0, dp(3), 0, 0) })
        editor = EditText(this).apply {
            hint = getString(R.string.input_note_hint)
            textSize = 16f
            minLines = 3
            maxLines = 7
            gravity = Gravity.TOP or Gravity.START
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            setTextColor(palette.ink)
            setHintTextColor(palette.hint)
            setPaddingRelative(dp(13), dp(11), dp(13), dp(11))
            background = rounded(palette.field, dp(15), palette.line)
            contentDescription = getString(R.string.capture_input_description)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = scheduleDraftPersistence()
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        capture.addView(editor, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(13) })
        val typeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        noteButton = typeButton(getString(R.string.type_note)) { selectType("note") }
        taskButton = typeButton(getString(R.string.type_task)) { selectType("task") }
        typeRow.addView(noteButton, weightedParams())
        typeRow.addView(taskButton, weightedParams(last = true))
        capture.addView(typeRow)
        saveButton = Button(this).apply {
            text = getString(R.string.save_note_button)
            isAllCaps = false
            textSize = 15f
            minHeight = dp(48)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = rounded(palette.teal, dp(14))
            setOnClickListener { saveTextEntry() }
        }
        capture.addView(saveButton, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(10) })
        voiceButton = Button(this).apply {
            text = getString(R.string.voice_record_button)
            isAllCaps = false
            textSize = 14f
            minHeight = dp(48)
            setTextColor(Color.WHITE)
            background = rounded(0xFF265D50.toInt(), dp(14))
            contentDescription = getString(R.string.voice_record_button)
            setOnClickListener { toggleRecording() }
        }
        capture.addView(voiceButton, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(8) })
        contentPage.addView(capture)
        selectType(selectedType)

        popupCard = buildPopupCard()
        contentPage.addView(popupCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(11) })

        contentPage.addView(label(getString(R.string.summary_title), 17f, palette.ink, true).apply { setPadding(0, dp(19), 0, dp(9)) })
        val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val noteStat = statCard(getString(R.string.stat_notes), palette.statNote, "note")
        val taskStat = statCard(getString(R.string.stat_tasks), palette.statTask, "task")
        val voiceStat = statCard(getString(R.string.stat_voice), palette.statVoice, "voice")
        countNotes = noteStat.second
        countTasks = taskStat.second
        countVoices = voiceStat.second
        stats.addView(noteStat.first, weightedStatParams())
        stats.addView(taskStat.first, weightedStatParams())
        stats.addView(voiceStat.first, weightedStatParams(last = true))
        contentPage.addView(stats)

        val listHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(18), 0, dp(9)) }
        listHeader.addView(label(getString(R.string.saved_title), 17f, palette.ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        listHeader.addView(label(getString(R.string.saved_device), 12f, palette.muted))
        val sortButton = Button(this).apply {
            text = getString(R.string.sort_button)
            isAllCaps = false
            minHeight = dp(48)
            textSize = 12f
            setTextColor(palette.teal)
            background = rounded(palette.soft, dp(12))
            setOnClickListener { chooseSort() }
        }
        listHeader.addView(sortButton, LinearLayout.LayoutParams(-2, dp(48)).apply { marginStart = dp(6) })
        contentPage.addView(listHeader)

        val searchRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        searchInput = EditText(this).apply {
            hint = getString(R.string.search_hint)
            setSingleLine(true)
            textSize = 14f
            setTextColor(palette.ink)
            setHintTextColor(palette.hint)
            setPaddingRelative(dp(13), dp(9), dp(13), dp(9))
            background = rounded(palette.surface, dp(13), palette.line)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (suppressSearchWatcher) return
                    searchQuery = s?.toString().orEmpty().trim()
                    scheduleDraftPersistence()
                    clearSearch.visibility = if (searchQuery.isBlank()) View.GONE else View.VISIBLE
                    mainHandler.removeCallbacks(debounceSearch)
                    mainHandler.postDelayed(debounceSearch, SEARCH_DEBOUNCE_MS)
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        searchRow.addView(searchInput, LinearLayout.LayoutParams(0, dp(50), 1f))
        clearSearch = imageButton(R.drawable.ic_close, R.string.clear_search).apply {
            visibility = View.GONE
            setOnClickListener { searchInput.text.clear(); searchInput.requestFocus() }
        }
        searchRow.addView(clearSearch, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(4) })
        contentPage.addView(searchRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })

        val filterScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipToPadding = false }
        val filters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            "all" to R.string.filter_all,
            "note" to R.string.filter_notes,
            "task" to R.string.filter_tasks,
            "voice" to R.string.filter_voice,
            "favorites" to R.string.filter_favorites,
            "archived" to R.string.filter_archived,
            "trash" to R.string.filter_trash
        ).forEach { (key, labelId) ->
            val button = filterButton(key, getString(labelId))
            filterButtons[key] = button
            filters.addView(button, LinearLayout.LayoutParams(-2, dp(48)).apply { marginEnd = dp(6) })
        }
        filterScroll.addView(filters)
        contentPage.addView(filterScroll)
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        contentPage.addView(listContainer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        attachLockCover()
    }

    private fun buildPopupCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(palette.surface, dp(16), palette.line)
        }
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_overlay)
            setColorFilter(palette.teal)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        card.addView(icon, LinearLayout.LayoutParams(dp(42), dp(42)))
        val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPaddingRelative(dp(9), 0, dp(7), 0) }
        copy.addView(label(getString(R.string.popup_title), 14f, palette.ink, true))
        popupStatus = label("", 12f, palette.muted).apply { setPadding(0, dp(3), 0, 0) }
        copy.addView(popupStatus)
        card.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        popupAction = Button(this).apply {
            text = getString(R.string.popup_enable)
            isAllCaps = false
            textSize = 12f
            minHeight = dp(48)
            setTextColor(Color.WHITE)
            background = rounded(palette.teal, dp(12))
            setOnClickListener { onPopupAction() }
        }
        card.addView(popupAction, LinearLayout.LayoutParams(-2, dp(48)))
        return card
    }

    private fun attachLockCover() {
        if (!::root.isInitialized || ::lockCover.isInitialized) return
        lockCover = AppLockHelper.createCover(this) { requestAuthentication() }
        root.addView(lockCover, FrameLayout.LayoutParams(-1, -1))
    }

    private fun setProtectedContentVisible(visible: Boolean) {
        if (::scroll.isInitialized) scroll.importantForAccessibility = if (visible) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        if (::lockCover.isInitialized) lockCover.visibility = if (visible) View.GONE else View.VISIBLE
    }

    private fun guardAppLock() {
        attachLockCover()
        if (!AppPreferences.appLockEnabled(this)) {
            AppLockSession.authenticated = true
            applySecurityFlags()
            setProtectedContentVisible(true)
            return
        }
        if (!AppLockHelper.supported(this)) {
            AppPreferences.setAppLockEnabled(this, false)
            AppLockSession.authenticated = true
            applySecurityFlags()
            setProtectedContentVisible(true)
            Toast.makeText(this, getString(R.string.lock_disabled_unsupported), Toast.LENGTH_LONG).show()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (AppLockSession.shouldLock(this)) {
            setProtectedContentVisible(false)
            requestAuthentication()
        } else setProtectedContentVisible(true)
    }

    private fun applySecurityFlags() {
        if (AppPreferences.appLockEnabled(this) || AppPreferences.protectScreenContent(this)) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun requestAuthentication() {
        if (lockPromptActive || !AppPreferences.appLockEnabled(this) || AppLockSession.authenticated) return
        if (!AppLockHelper.supported(this)) {
            guardAppLock()
            return
        }
        lockPromptActive = true
        AppLockHelper.authenticate(this, {
            lockPromptActive = false
            restoreDeferredDraftIfAllowed()
            setProtectedContentVisible(true)
            if (!remindersInitialized) {
                remindersInitialized = true
                ioExecutor.execute { ReminderScheduler.rescheduleAll(applicationContext) }
            }
            loadEntries()
        }, { message ->
            lockPromptActive = false
            if (message.isNotBlank()) Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        })
    }

    private fun setSystemBarSecurity() = applySecurityFlags()

    private fun restoreDeferredDraftIfAllowed() {
        if (draftRestored || (AppPreferences.appLockEnabled(this) && !AppLockSession.authenticated)) return
        draftRestored = true
        val state = deferredDraftState
        deferredDraftState = null
        restoreDraftState(state)
    }

    private fun restoreDraftState(state: Bundle?) {
        val prefs = getPreferences(MODE_PRIVATE)
        val draft = state?.getString(STATE_DRAFT) ?: prefs.getString(PREF_DRAFT_TEXT, "").orEmpty()
        if (draft.isNotEmpty() && editor.text.isNullOrEmpty()) editor.setText(draft)
        val type = state?.getString(STATE_TYPE) ?: prefs.getString(PREF_DRAFT_TYPE, "note")
        if (type == "note" || type == "task") selectType(type)
        val query = state?.getString(STATE_QUERY) ?: prefs.getString(PREF_SEARCH_QUERY, "")
        query?.let { queryText ->
            suppressSearchWatcher = true
            searchInput.setText(queryText)
            searchInput.setSelection(searchInput.text.length)
            suppressSearchWatcher = false
            searchQuery = queryText
            clearSearch.visibility = if (queryText.isBlank()) View.GONE else View.VISIBLE
        }
        sortMode = state?.getString(STATE_SORT) ?: sortMode
        scheduleDraftPersistence()
    }

    private fun handleIncomingIntent(incoming: Intent?) {
        if (incoming == null) return
        val sharedText = when (incoming.action) {
            Intent.ACTION_SEND -> incoming.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_PROCESS_TEXT -> incoming.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            else -> null
        }
        if (!sharedText.isNullOrBlank() && ::editor.isInitialized) {
            selectType("note")
            editor.setText(sharedText.take(MAX_ENTRY_TEXT))
            editor.setSelection(editor.text.length)
            return
        }
        val voiceRequest = incoming.getBooleanExtra(EXTRA_START_VOICE_NOTE, false)
        val type = incoming.getStringExtra(EXTRA_CAPTURE_TYPE)
        if (voiceRequest || type == "voice") {
            incoming.removeExtra(EXTRA_START_VOICE_NOTE)
            incoming.removeExtra(EXTRA_CAPTURE_TYPE)
            voiceButton.post { voiceButton.requestFocus(); Toast.makeText(this, getString(R.string.voice_ready_to_record), Toast.LENGTH_SHORT).show() }
            return
        }
        if (type in setOf("note", "task") && ::editor.isInitialized) {
            incoming.removeExtra(EXTRA_CAPTURE_TYPE)
            selectType(type!!)
            editor.post { editor.requestFocus(); (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT) }
        }
    }

    private fun loadEntries() {
        if (!::db.isInitialized || (AppPreferences.appLockEnabled(this) && !AppLockSession.authenticated)) return
        ioExecutor.execute {
            val result = runCatching { db.all(includeArchived = true, includeDeleted = true) }.getOrElse { emptyList() }
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                entriesCache = result
                renderEntries()
                pendingEntryOpenId?.let { id ->
                    pendingEntryOpenId = null
                    entriesCache.firstOrNull { it.id == id && it.deletedAt == null }?.let { openEntry(it) }
                }
            }
        }
    }

    private fun renderEntries() {
        if (!::listContainer.isInitialized || (AppPreferences.appLockEnabled(this) && !AppLockSession.authenticated)) return
        listContainer.removeAllViews()
        val active = entriesCache.filter { it.deletedAt == null && !it.archived }
        countNotes.text = active.count { it.type == "note" }.toString()
        countTasks.text = active.count { it.type == "task" && !it.done }.toString()
        countVoices.text = active.count { it.type == "voice" }.toString()
        val query = searchQuery.lowercase(Locale.ROOT)
        var items = entriesCache.filter { entry ->
            val filterMatches = when (activeFilter) {
                "all" -> entry.deletedAt == null && !entry.archived
                "favorites" -> entry.deletedAt == null && !entry.archived && entry.favorite
                "archived" -> entry.deletedAt == null && entry.archived
                "trash" -> entry.deletedAt != null
                else -> entry.deletedAt == null && !entry.archived && entry.type == activeFilter
            }
            val searchable = listOfNotNull(entry.title, entry.content, entry.transcript, entry.tag).joinToString(" ").lowercase(Locale.ROOT)
            filterMatches && (query.isBlank() || searchable.contains(query))
        }
        items = when (sortMode) {
            "created" -> items.sortedWith(compareByDescending<Entry> { it.pinned }.thenByDescending { it.createdAt })
            "oldest" -> items.sortedWith(compareByDescending<Entry> { it.pinned }.thenBy { it.createdAt })
            else -> items.sortedWith(compareByDescending<Entry> { it.pinned }.thenByDescending { it.updatedAt })
        }
        if (items.isEmpty()) {
            val empty = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(18), dp(18), dp(18), dp(18))
                background = rounded(palette.surface, dp(16), palette.line)
            }
            empty.addView(label(getString(if (entriesCache.isEmpty()) R.string.empty_all_title else R.string.empty_filter_title), 15f, palette.ink, true))
            empty.addView(label(getString(R.string.empty_subtitle), 13f, palette.muted).apply { setPadding(0, dp(6), 0, 0) })
            if (activeFilter != "all") empty.addView(Button(this).apply {
                text = getString(R.string.filter_reset)
                isAllCaps = false
                minHeight = dp(48)
                setOnClickListener { setFilter("all") }
            })
            listContainer.addView(empty)
            return
        }
        items.forEach { entry -> listContainer.addView(entryCard(entry), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }) }
    }

    private fun entryCard(entry: Entry): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), dp(10), dp(9), dp(8))
            background = rounded(palette.surface, dp(17), palette.line)
            isFocusable = true
            isClickable = true
            contentDescription = getString(R.string.open_item, displayEntryText(entry))
            setOnClickListener { if (entry.deletedAt == null) openEntry(entry) else showTrashMenu(this, entry) }
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (entry.type == "task" && entry.deletedAt == null) {
            val check = CheckBox(this).apply {
                isChecked = entry.done
                minWidth = dp(48)
                minHeight = dp(48)
                buttonTintList = android.content.res.ColorStateList.valueOf(palette.teal)
                contentDescription = getString(if (entry.done) R.string.task_mark_open else R.string.task_mark_done, displayEntryText(entry))
                setOnCheckedChangeListener { _, checked -> updateTaskDone(entry, checked) }
            }
            row.addView(check, LinearLayout.LayoutParams(dp(48), dp(48)))
        } else {
            val symbol = ImageView(this).apply {
                setImageResource(when (entry.type) { "voice" -> R.drawable.ic_voice; "task" -> R.drawable.ic_task; else -> R.drawable.ic_note })
                setColorFilter(palette.teal)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(11), dp(11), dp(11), dp(11))
                background = rounded(when (entry.type) { "voice" -> palette.badgeVoice; "task" -> palette.badgeTask; else -> palette.badgeNote }, dp(13))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            row.addView(symbol, LinearLayout.LayoutParams(dp(46), dp(46)))
        }
        val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPaddingRelative(dp(10), 0, dp(5), 0) }
        val shownTitle = entry.title?.takeIf(String::isNotBlank) ?: when (entry.type) {
            "voice" -> getString(R.string.item_voice)
            else -> entry.content
        }
        details.addView(label(shownTitle.ifBlank { getString(R.string.item_no_title) }, 15f, if (entry.done) palette.muted else palette.ink, true).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            if (entry.done) paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
        })
        if (entry.type == "voice" && !entry.transcript.isNullOrBlank()) details.addView(label(entry.transcript!!, 13f, palette.muted).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(3), 0, 0)
        })
        val created = DateUtils.getRelativeTimeSpanString(entry.createdAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        val typeName = when (entry.type) { "task" -> getString(R.string.item_task); "voice" -> getString(R.string.item_voice); else -> getString(R.string.item_note) }
        details.addView(label(getString(R.string.item_meta, typeName, created), 12f, palette.muted).apply { setPadding(0, dp(3), 0, 0) })
        if (entry.remindAt != null && !entry.done && entry.deletedAt == null) details.addView(label(getString(R.string.reminder_at, formatDate(entry.remindAt)), 12f, palette.teal).apply { setPadding(0, dp(3), 0, 0) })
        if (entry.deletedAt != null) details.addView(label(getString(R.string.trash_deleted), 12f, palette.muted).apply { setPadding(0, dp(3), 0, 0) })
        if (entry.archived) details.addView(label(getString(R.string.archived_label), 12f, palette.muted).apply { setPadding(0, dp(3), 0, 0) })
        row.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
        if (entry.pinned) row.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_pin)
            setColorFilter(palette.teal)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(30), dp(42)))
        val favorite = imageButton(if (entry.favorite) R.drawable.ic_favorite_on else R.drawable.ic_favorite_off,
            if (entry.favorite) R.string.favorite_remove else R.string.favorite_add).apply {
            setColorFilter(if (entry.favorite) 0xFFB87900.toInt() else palette.muted)
            setOnClickListener { toggleFavorite(entry) }
        }
        row.addView(favorite, LinearLayout.LayoutParams(dp(48), dp(48)))
        val more = imageButton(R.drawable.ic_more, R.string.more_actions).apply { setOnClickListener { showEntryMenu(this, entry) } }
        row.addView(more, LinearLayout.LayoutParams(dp(48), dp(48)))
        card.addView(row)
        if (entry.tag.isNotBlank() && entry.deletedAt == null) {
            val tags = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
            EntryDb.normalizeTags(entry.tag).split(", ").filter(String::isNotBlank).forEach { tag ->
                val chip = Button(this).apply {
                    text = "#${tag}"
                    isAllCaps = false
                    textSize = 12f
                    minHeight = dp(48)
                    setTextColor(palette.teal)
                    background = rounded(palette.soft, dp(12))
                    contentDescription = getString(R.string.filter_tag, tag)
                    setOnClickListener { searchInput.setText(tag) }
                }
                tags.addView(chip, LinearLayout.LayoutParams(-2, dp(48)).apply { marginEnd = dp(5) })
            }
            card.addView(tags)
        }
        return card
    }

    private fun showEntryMenu(anchor: View, entry: Entry) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(Menu.NONE, MENU_EDIT, Menu.NONE, getString(R.string.edit_item))
        popup.menu.add(Menu.NONE, MENU_COPY, Menu.NONE, getString(R.string.copy_item))
        popup.menu.add(Menu.NONE, MENU_SHARE, Menu.NONE, getString(R.string.share_item))
        popup.menu.add(Menu.NONE, MENU_DUPLICATE, Menu.NONE, getString(R.string.duplicate_item))
        popup.menu.add(Menu.NONE, MENU_PIN, Menu.NONE, getString(if (entry.pinned) R.string.unpin_item else R.string.pin_item))
        popup.menu.add(Menu.NONE, MENU_TAG, Menu.NONE, getString(if (entry.tag.isBlank()) R.string.tag_add else R.string.tag_edit))
        if (entry.type == "task" && !entry.done) popup.menu.add(Menu.NONE, MENU_REMINDER, Menu.NONE, getString(if (entry.remindAt == null) R.string.reminder_add else R.string.reminder_change))
        if (entry.type == "voice") {
            popup.menu.add(Menu.NONE, MENU_PLAY, Menu.NONE, getString(R.string.play_button))
            if (VoiceTranscriber.isSupported(this)) popup.menu.add(Menu.NONE, MENU_TRANSCRIBE, Menu.NONE, getString(R.string.transcribe_button))
        }
        popup.menu.add(Menu.NONE, MENU_ARCHIVE, Menu.NONE, getString(if (entry.archived) R.string.unarchive_item else R.string.archive_item))
        popup.menu.add(Menu.NONE, MENU_DELETE, Menu.NONE, getString(R.string.delete_item))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_EDIT -> openEntry(entry)
                MENU_COPY -> copyEntry(entry)
                MENU_SHARE -> shareEntry(entry)
                MENU_DUPLICATE -> duplicateEntry(entry)
                MENU_PIN -> setPinned(entry, !entry.pinned)
                MENU_TAG -> editTag(entry)
                MENU_REMINDER -> requestReminderPermission(entry)
                MENU_PLAY -> playVoice(entry)
                MENU_TRANSCRIBE -> chooseTranscriptionLanguage(entry)
                MENU_ARCHIVE -> setArchived(entry, !entry.archived)
                MENU_DELETE -> confirmDelete(entry)
            }
            true
        }
        popup.show()
    }

    private fun showTrashMenu(anchor: View, entry: Entry) {
        PopupMenu(this, anchor).apply {
            menu.add(Menu.NONE, MENU_RESTORE, Menu.NONE, getString(R.string.restore_item))
            menu.add(Menu.NONE, MENU_PERMANENT_DELETE, Menu.NONE, getString(R.string.delete_permanently))
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_RESTORE -> restoreEntry(entry)
                    MENU_PERMANENT_DELETE -> AlertDialog.Builder(this@MainActivity)
                        .setTitle(getString(R.string.delete_permanently))
                        .setMessage(getString(R.string.delete_permanently_warning))
                        .setPositiveButton(getString(R.string.delete_item)) { _, _ -> permanentlyDelete(entry) }
                        .setNegativeButton(getString(R.string.cancel), null)
                        .show()
                }
                true
            }
        }.show()
    }

    private fun openEntry(entry: Entry) {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(4)) }
        val title = EditText(this).apply {
            hint = getString(R.string.item_title_hint)
            setText(entry.title.orEmpty())
            setSingleLine(true)
            textSize = 16f
            setTextColor(palette.ink)
            setHintTextColor(palette.hint)
            contentDescription = getString(R.string.item_title_hint)
        }
        panel.addView(title, LinearLayout.LayoutParams(-1, dp(54)))
        var body: EditText? = null
        var transcript: EditText? = null
        if (entry.type == "voice") {
            transcript = EditText(this).apply {
                hint = getString(R.string.transcript_hint)
                setText(entry.transcript.orEmpty())
                minLines = 4
                maxLines = 10
                gravity = Gravity.TOP or Gravity.START
                textDirection = View.TEXT_DIRECTION_FIRST_STRONG
                setTextColor(palette.ink)
                setHintTextColor(palette.hint)
            }
            panel.addView(transcript, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        } else {
            body = EditText(this).apply {
                hint = getString(R.string.item_content_hint)
                setText(entry.content)
                minLines = 6
                maxLines = 16
                gravity = Gravity.TOP or Gravity.START
                textDirection = View.TEXT_DIRECTION_FIRST_STRONG
                setTextColor(palette.ink)
                setHintTextColor(palette.hint)
            }
            panel.addView(body, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        val scrollPanel = ScrollView(this).apply { addView(panel); isFillViewport = true }
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.edit_item))
            .setView(scrollPanel)
            .setPositiveButton(getString(R.string.save_button), null)
            .setNeutralButton(getString(R.string.share_item)) { _, _ -> shareEntry(entry, title.text.toString(), body?.text?.toString(), transcript?.text?.toString()) }
            .setNegativeButton(getString(R.string.cancel), null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newTitle = title.text.toString().trim().take(MAX_TITLE_LENGTH)
                val newContent = body?.text?.toString()?.trim()?.take(MAX_ENTRY_TEXT)
                val newTranscript = transcript?.text?.toString()?.trim()?.take(MAX_ENTRY_TEXT)
                if (entry.type != "voice" && newContent.isNullOrBlank()) {
                    body?.error = getString(R.string.error_empty)
                    return@setOnClickListener
                }
                ioExecutor.execute {
                    db.setTitle(entry.id, newTitle)
                    if (newContent != null) db.setContent(entry.id, newContent)
                    if (newTranscript != null) db.setTranscript(entry.id, newTranscript)
                    db.get(entry.id)?.let { NotificationHelper.showItem(this, it) }
                    mainHandler.post { dialog.dismiss(); loadEntries(); Toast.makeText(this, getString(R.string.item_updated), Toast.LENGTH_SHORT).show() }
                }
            }
        }
        dialog.show()
    }

    private fun saveTextEntry() {
        val value = editor.text.toString().trim().take(MAX_ENTRY_TEXT)
        if (value.isBlank()) { editor.error = getString(R.string.error_empty); editor.requestFocus(); return }
        saveButton.isEnabled = false
        ioExecutor.execute {
            val id = db.add(selectedType, value)
            val saved = db.get(id)
            if (saved != null) NotificationHelper.showItem(this, saved)
            mainHandler.post {
                if (isDestroyed) return@post
                saveButton.isEnabled = true
                editor.text.clear()
                getPreferences(MODE_PRIVATE).edit().remove(PREF_DRAFT_TEXT).putString(PREF_DRAFT_TYPE, selectedType).apply()
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(editor.windowToken, 0)
                Toast.makeText(this, getString(if (selectedType == "task") R.string.saved_task_toast else R.string.saved_note_toast), Toast.LENGTH_SHORT).show()
                QuickNoteWidgetProvider.refreshAll(this)
                loadEntries()
            }
        }
    }

    private fun selectType(type: String) {
        selectedType = if (type == "task") "task" else "note"
        getPreferences(MODE_PRIVATE).edit().putString(PREF_DRAFT_TYPE, selectedType).apply()
        val noteSelected = selectedType == "note"
        noteButton.background = rounded(if (noteSelected) palette.teal else palette.surface, dp(12), if (noteSelected) null else palette.line)
        taskButton.background = rounded(if (!noteSelected) palette.teal else palette.surface, dp(12), if (!noteSelected) null else palette.line)
        noteButton.setTextColor(if (noteSelected) Color.WHITE else palette.ink)
        taskButton.setTextColor(if (!noteSelected) Color.WHITE else palette.ink)
        editor.hint = getString(if (selectedType == "task") R.string.input_task_hint else R.string.input_note_hint)
        saveButton.text = getString(if (selectedType == "task") R.string.save_task_button else R.string.save_note_button)
    }

    private fun typeButton(title: String, click: () -> Unit) = Button(this).apply {
        text = title
        isAllCaps = false
        textSize = 14f
        minHeight = dp(48)
        setPaddingRelative(dp(6), 0, dp(6), 0)
        background = rounded(palette.surface, dp(12), palette.line)
        setOnClickListener { click() }
    }

    private fun toggleRecording() {
        if (recording) stopRecording(save = true)
        else if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording()
        else requestAudioPermission()
    }

    private fun requestAudioPermission() {
        val requestedBefore = getPreferences(MODE_PRIVATE).getBoolean(PREF_AUDIO_REQUESTED, false)
        if (requestedBefore && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.audio_permission_title))
                .setMessage(getString(R.string.audio_permission_settings_message))
                .setPositiveButton(getString(R.string.open_settings)) { _, _ -> openAppSettings() }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        } else if (shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.audio_permission_title))
                .setMessage(getString(R.string.audio_permission_rationale))
                .setPositiveButton(getString(R.string.continue_label)) { _, _ -> launchAudioPermission() }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        } else launchAudioPermission()
    }

    private fun launchAudioPermission() {
        getPreferences(MODE_PRIVATE).edit().putBoolean(PREF_AUDIO_REQUESTED, true).apply()
        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun explainDeniedAudioPermission() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.audio_permission_title))
            .setMessage(getString(R.string.audio_permission_denied))
            .setPositiveButton(getString(R.string.open_settings)) { _, _ -> openAppSettings() }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun startRecording() {
        val available = runCatching { StatFs(filesDir.absolutePath).availableBytes }.getOrDefault(0L)
        if (available < MIN_RECORDING_SPACE_BYTES) {
            Toast.makeText(this, getString(R.string.recording_storage_low), Toast.LENGTH_LONG).show()
            return
        }
        val file = try { File.createTempFile("voice_note_", ".m4a", filesDir) }
        catch (_: Exception) { Toast.makeText(this, getString(R.string.record_failed), Toast.LENGTH_LONG).show(); return }
        try {
            val active = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()
            active.setAudioSource(MediaRecorder.AudioSource.MIC)
            active.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            active.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            active.setAudioEncodingBitRate(96_000)
            active.setAudioSamplingRate(44_100)
            active.setOutputFile(file.absolutePath)
            active.prepare()
            active.start()
            audioRecorder = active
            recordingFile = file
            recordingStartedAt = System.currentTimeMillis()
            recording = true
            voiceButton.background = rounded(0xFFB84438.toInt(), dp(14))
            voiceButton.text = getString(R.string.recording_elapsed, "00:00")
            mainHandler.removeCallbacks(recordTicker)
            mainHandler.postDelayed(recordTicker, 1000L)
            voiceButton.announceForAccessibility(getString(R.string.record_started))
        } catch (_: Exception) {
            runCatching { audioRecorder?.release() }
            audioRecorder = null
            file.delete()
            recordingFile = null
            recording = false
            Toast.makeText(this, getString(R.string.record_failed), Toast.LENGTH_LONG).show()
        }
    }

    private fun stopRecording(save: Boolean) {
        if (!recording && audioRecorder == null) return
        mainHandler.removeCallbacks(recordTicker)
        val file = recordingFile
        val duration = (System.currentTimeMillis() - recordingStartedAt).coerceAtLeast(0L)
        var valid = false
        try { audioRecorder?.stop(); valid = file?.isFile == true && file.length() > 0L }
        catch (_: Exception) { file?.delete() }
        finally {
            runCatching { audioRecorder?.reset() }
            runCatching { audioRecorder?.release() }
            audioRecorder = null
            recording = false
            recordingFile = null
            voiceButton.text = getString(R.string.voice_record_button)
            voiceButton.background = rounded(0xFF265D50.toInt(), dp(14))
        }
        if (save && valid && file != null) {
            ioExecutor.execute {
                val id = db.add("voice", "", audioPath = file.absolutePath, durationMs = duration)
                db.get(id)?.let { NotificationHelper.showItem(this, it) }
                mainHandler.post {
                    if (isDestroyed) return@post
                    voiceButton.announceForAccessibility(getString(R.string.voice_saved))
                    Toast.makeText(this, getString(R.string.voice_saved), Toast.LENGTH_SHORT).show()
                    QuickNoteWidgetProvider.refreshAll(this)
                    loadEntries()
                }
            }
        } else if (file != null && !valid) file.delete()
    }

    override fun onStop() {
        if (recording) stopRecording(save = true)
        VoiceTranscriber.cancel(this)
        transcriptionDialog?.dismiss()
        transcriptionDialog = null
        mainHandler.removeCallbacks(persistDraft)
        persistDraft.run()
        super.onStop()
    }

    private fun sweepOrphanAudioFiles() {
        ioExecutor.execute {
            val referenced = runCatching { db.all(includeArchived = true, includeDeleted = true).mapNotNull { it.audioPath }.toSet() }.getOrDefault(emptySet())
            val cutoff = System.currentTimeMillis() - ORPHAN_AUDIO_GRACE_MS
            filesDir.listFiles()?.filter { file ->
                (file.name.startsWith("voice_note_") || file.name.startsWith("restored_")) && file.lastModified() < cutoff && file.absolutePath !in referenced
            }?.forEach { it.delete() }
            db.purgeExpiredTrash(System.currentTimeMillis() - TRASH_RETENTION_MS).forEach { it.audioPath?.let(::File)?.delete() }
        }
    }

    private fun updateTaskDone(entry: Entry, done: Boolean) {
        ioExecutor.execute {
            db.setDone(entry.id, done)
            if (done) {
                ReminderScheduler.cancel(this, entry.id)
                NotificationHelper.cancelReminder(this, entry.id)
            } else {
                val at = entry.remindAt
                if (at != null && at > System.currentTimeMillis()) ReminderScheduler.schedule(this, entry.id, at)
                else if (at != null) NotificationHelper.showTaskReminder(this, entry.copy(done = false))
            }
            db.get(entry.id)?.let { NotificationHelper.showItem(this, it) }
            mainHandler.post { loadEntries(); QuickNoteWidgetProvider.refreshAll(this) }
        }
    }

    private fun toggleFavorite(entry: Entry) {
        ioExecutor.execute {
            db.setFavorite(entry.id, !entry.favorite)
            db.get(entry.id)?.let { NotificationHelper.showItem(this, it) }
            mainHandler.post { loadEntries() }
        }
    }

    private fun setPinned(entry: Entry, pinned: Boolean) {
        ioExecutor.execute { db.setPinned(entry.id, pinned); mainHandler.post { loadEntries() } }
    }

    private fun setArchived(entry: Entry, archived: Boolean) {
        ioExecutor.execute {
            db.setArchived(entry.id, archived)
            if (archived) {
                ReminderScheduler.cancel(this, entry.id)
                NotificationHelper.cancelReminder(this, entry.id)
            } else if (!entry.done && entry.remindAt != null && entry.remindAt > System.currentTimeMillis()) {
                ReminderScheduler.schedule(this, entry.id, entry.remindAt)
            }
            mainHandler.post { QuickNoteWidgetProvider.refreshAll(this); loadEntries() }
        }
    }

    private fun duplicateEntry(entry: Entry) {
        ioExecutor.execute {
            val duplicateAudio = if (entry.type == "voice" && entry.audioPath != null) {
                runCatching {
                    val source = File(entry.audioPath)
                    if (!source.isFile) null else File.createTempFile("voice_note_copy_", ".m4a", filesDir).also { source.copyTo(it, overwrite = true) }.absolutePath
                }.getOrElse { error ->
                    android.util.Log.e("QuickNote", "Could not copy voice recording", error)
                    mainHandler.post { Toast.makeText(this, getString(R.string.voice_copy_failed), Toast.LENGTH_LONG).show() }
                    return@execute
                }
            } else null
            val newId = db.add(entry.type, entry.content, duplicateAudio, entry.tag, entry.favorite, null, entry.title, entry.transcript, entry.durationMs)
            db.get(newId)?.let { NotificationHelper.showItem(this, it) }
            mainHandler.post { loadEntries(); Toast.makeText(this, getString(R.string.item_duplicated), Toast.LENGTH_SHORT).show() }
        }
    }

    private fun copyEntry(entry: Entry) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), shareText(entry)))
        Toast.makeText(this, getString(R.string.item_copied), Toast.LENGTH_SHORT).show()
    }

    private fun shareEntry(entry: Entry, title: String? = entry.title, content: String? = entry.content, transcript: String? = entry.transcript) {
        val text = buildList {
            title?.takeIf(String::isNotBlank)?.let(::add)
            content?.takeIf(String::isNotBlank)?.let(::add)
            transcript?.takeIf(String::isNotBlank)?.let(::add)
        }.joinToString("\n\n").ifBlank { shareText(entry) }
        AppLockSession.markExternalIntentLaunch()
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, getString(R.string.share_item)))
    }

    private fun shareText(entry: Entry): String = buildList {
        entry.title?.takeIf(String::isNotBlank)?.let(::add)
        entry.content.takeIf(String::isNotBlank)?.let(::add)
        entry.transcript?.takeIf(String::isNotBlank)?.let(::add)
    }.joinToString("\n\n")

    private fun editTag(entry: Entry) {
        val input = EditText(this).apply {
            setText(entry.tag)
            hint = getString(R.string.tags_hint)
            setSingleLine(true)
            contentDescription = getString(R.string.tags_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.tag_edit_title))
            .setMessage(getString(R.string.tags_multiple_help))
            .setView(input)
            .setPositiveButton(getString(R.string.save_button)) { _, _ ->
                ioExecutor.execute { db.setTag(entry.id, input.text.toString()); mainHandler.post { loadEntries() } }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun requestReminderPermission(entry: Entry) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingReminderEntry = entry
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.reminder_permission_title))
                .setMessage(getString(R.string.reminder_permission_message))
                .setPositiveButton(getString(R.string.continue_label)) { _, _ -> notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                .setNegativeButton(getString(R.string.cancel)) { _, _ -> pendingReminderEntry = null }
                .show()
            return
        }
        if (!androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            explainNotificationsBlocked(entry)
            return
        }
        chooseReminderPreset(entry)
    }

    private fun explainNotificationsBlocked(entry: Entry) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.reminder_notification_blocked_title))
            .setMessage(getString(R.string.reminder_notification_blocked_message))
            .setPositiveButton(getString(R.string.open_settings)) { _, _ ->
                pendingReminderEntry = entry
                openNotificationSettings()
            }
            .setNeutralButton(getString(R.string.schedule_anyway)) { _, _ -> chooseReminderPreset(entry) }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun chooseReminderPreset(entry: Entry) {
        val options = arrayOf(
            getString(R.string.reminder_in_one_hour),
            getString(R.string.reminder_tonight),
            getString(R.string.reminder_tomorrow_morning),
            getString(R.string.reminder_pick_time)
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.reminder_add))
            .setItems(options) { _, index ->
                val time = when (index) {
                    0 -> System.currentTimeMillis() + 60 * 60 * 1000L
                    1 -> Calendar.getInstance().run {
                        set(Calendar.HOUR_OF_DAY, 20); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                        if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
                        timeInMillis
                    }
                    2 -> Calendar.getInstance().run {
                        add(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0); timeInMillis
                    }
                    else -> { chooseReminderDateTime(entry); return@setItems }
                }
                saveReminder(entry, time)
            }
            .setNeutralButton(getString(R.string.reminder_remove)) { _, _ ->
                ioExecutor.execute { db.setReminder(entry.id, null); ReminderScheduler.cancel(this, entry.id); mainHandler.post { loadEntries() } }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun chooseReminderDateTime(entry: Entry) {
        val calendar = Calendar.getInstance().apply { timeInMillis = entry.remindAt ?: System.currentTimeMillis(); if (entry.remindAt == null) add(Calendar.HOUR_OF_DAY, 1) }
        DatePickerDialog(this, { _, year, month, day ->
            calendar.set(Calendar.YEAR, year); calendar.set(Calendar.MONTH, month); calendar.set(Calendar.DAY_OF_MONTH, day)
            TimePickerDialog(this, { _, hour, minute ->
                calendar.set(Calendar.HOUR_OF_DAY, hour); calendar.set(Calendar.MINUTE, minute); calendar.set(Calendar.SECOND, 0); calendar.set(Calendar.MILLISECOND, 0)
                saveReminder(entry, calendar.timeInMillis)
            }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), android.text.format.DateFormat.is24HourFormat(this)).show()
        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun saveReminder(entry: Entry, at: Long) {
        if (at <= System.currentTimeMillis()) {
            Toast.makeText(this, getString(R.string.reminder_must_be_future), Toast.LENGTH_SHORT).show()
            return
        }
        ioExecutor.execute {
            db.setReminder(entry.id, at)
            ReminderScheduler.schedule(this, entry.id, at)
            mainHandler.post {
                Toast.makeText(this, getString(R.string.reminder_saved), Toast.LENGTH_SHORT).show()
                loadEntries()
            }
        }
    }

    private fun chooseTranscriptionLanguage(entry: Entry) {
        if (!VoiceTranscriber.isSupported(this)) {
            Toast.makeText(this, getString(if (Build.VERSION.SDK_INT < 33) R.string.transcription_requires_android_13 else R.string.transcription_unavailable), Toast.LENGTH_LONG).show()
            return
        }
        val options = arrayOf(getString(R.string.recognition_app_language), getString(R.string.language_english), getString(R.string.language_arabic))
        val codes = arrayOf("app", "en", "ar")
        val selected = codes.indexOf(AppPreferences.recognitionLanguage(this)).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.transcription_language_title))
            .setSingleChoiceItems(options, selected) { dialog, which ->
                AppPreferences.setRecognitionLanguage(this, codes[which])
                dialog.dismiss()
                transcribe(entry)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun transcribe(entry: Entry) {
        val progress = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(12), dp(22), dp(12))
            addView(android.widget.ProgressBar(this@MainActivity), LinearLayout.LayoutParams(dp(28), dp(28)))
            addView(label(getString(R.string.transcription_starting), 14f, palette.ink).apply { setPaddingRelative(dp(14), 0, 0, 0) })
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.transcribe_button))
            .setView(progress)
            .setNegativeButton(getString(R.string.cancel), null)
            .create()
        transcriptionDialog = dialog
        dialog.setOnCancelListener { VoiceTranscriber.cancel(this); transcriptionDialog = null }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                VoiceTranscriber.cancel(this)
                transcriptionDialog = null
                dialog.dismiss()
            }
        }
        dialog.show()
        VoiceTranscriber.transcribe(this, entry, AppPreferences.recognitionLanguage(this), { text ->
            dialog.dismiss()
            if (transcriptionDialog === dialog) transcriptionDialog = null
            ioExecutor.execute {
                db.setTranscript(entry.id, text)
                db.get(entry.id)?.let { NotificationHelper.showItem(this, it) }
                mainHandler.post { loadEntries(); Toast.makeText(this, getString(R.string.transcription_saved), Toast.LENGTH_SHORT).show() }
            }
        }, { message ->
            dialog.dismiss()
            if (transcriptionDialog === dialog) transcriptionDialog = null
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        })
    }

    private fun playVoice(entry: Entry) {
        val path = entry.audioPath?.let(::File)
        if (path == null || !path.isFile) { Toast.makeText(this, getString(R.string.voice_play_failed), Toast.LENGTH_SHORT).show(); return }
        try {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(path.absolutePath)
                setOnPreparedListener { it.start(); playingEntryId = entry.id }
                setOnCompletionListener { it.release(); if (player === it) { player = null; playingEntryId = null } }
                setOnErrorListener { mp, _, _ -> mp.release(); if (player === mp) player = null; playingEntryId = null; Toast.makeText(this@MainActivity, getString(R.string.voice_play_failed), Toast.LENGTH_SHORT).show(); true }
                prepareAsync()
            }
        } catch (_: Exception) {
            player?.release(); player = null; playingEntryId = null
            Toast.makeText(this, getString(R.string.voice_play_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete(entry: Entry) {
        val builder = AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_item))
            .setMessage(getString(if (entry.type == "voice") R.string.delete_voice_warning else R.string.delete_item_warning))
            .setPositiveButton(getString(R.string.delete_item)) { _, _ -> deleteEntry(entry) }
            .setNegativeButton(getString(R.string.cancel), null)
        builder.show()
    }

    private fun deleteEntry(entry: Entry) {
        undoRunnable?.let(undoHandler::removeCallbacks)
        undoEntry?.let(::finalizeTrash)
        ioExecutor.execute {
            val trashed = db.trash(entry.id) ?: return@execute
            ReminderScheduler.cancel(this, entry.id)
            NotificationHelper.cancelAllEntryNotifications(this, entry)
            mainHandler.post {
                if (isDestroyed) return@post
                showUndoBar(trashed)
                QuickNoteWidgetProvider.refreshAll(this)
                loadEntries()
            }
        }
    }

    private fun showUndoBar(entry: Entry) {
        undoView?.let { root.removeView(it) }
        undoEntry = entry
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(4), dp(8), dp(4))
            background = rounded(if (palette.dark) 0xFF34433B.toInt() else 0xFF28352F.toInt(), dp(14))
            contentDescription = getString(R.string.deleted_undo_message)
        }
        bar.addView(label(getString(R.string.deleted_undo_message), 14f, Color.WHITE), LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(Button(this).apply {
            text = getString(R.string.undo_action)
            isAllCaps = false
            minHeight = dp(48)
            setTextColor(Color.WHITE)
            background = rounded(palette.teal, dp(12))
            setOnClickListener {
                undoRunnable?.let(undoHandler::removeCallbacks)
                undoEntry?.let { deleted ->
                    ioExecutor.execute {
                        db.restoreDeleted(deleted)
                        if (!deleted.done && deleted.remindAt != null && deleted.remindAt > System.currentTimeMillis()) ReminderScheduler.schedule(this@MainActivity, deleted.id, deleted.remindAt)
                        mainHandler.post { loadEntries(); QuickNoteWidgetProvider.refreshAll(this@MainActivity) }
                    }
                }
                undoEntry = null
                undoView?.let(root::removeView)
                undoView = null
            }
        })
        root.addView(bar, FrameLayout.LayoutParams(-1, dp(60), Gravity.BOTTOM).apply { setMargins(dp(12), 0, dp(12), dp(12)) })
        undoView = bar
        val task = Runnable { undoEntry?.let(::finalizeTrash); undoEntry = null; undoView?.let(root::removeView); undoView = null }
        undoRunnable = task
        undoHandler.postDelayed(task, UNDO_WINDOW_MS)
    }

    private fun finalizeTrash(entry: Entry) {
        ioExecutor.execute {
            db.purge(entry.id)?.audioPath?.let(::File)?.delete()
            mainHandler.post { QuickNoteWidgetProvider.refreshAll(this) }
        }
    }

    private fun restoreEntry(entry: Entry) {
        ioExecutor.execute {
            db.restoreDeleted(entry)
            if (!entry.done && entry.remindAt != null && entry.remindAt > System.currentTimeMillis()) ReminderScheduler.schedule(this, entry.id, entry.remindAt)
            mainHandler.post { loadEntries(); QuickNoteWidgetProvider.refreshAll(this) }
        }
    }

    private fun permanentlyDelete(entry: Entry) {
        ioExecutor.execute {
            ReminderScheduler.cancel(this, entry.id)
            NotificationHelper.cancelAllEntryNotifications(this, entry)
            db.purge(entry.id)?.audioPath?.let(::File)?.delete()
            mainHandler.post { loadEntries(); QuickNoteWidgetProvider.refreshAll(this) }
        }
    }

    private fun setFilter(filter: String) {
        activeFilter = filter
        filterButtons.forEach { (key, button) ->
            val selected = key == filter
            button.background = rounded(if (selected) palette.teal else palette.surface, dp(18), if (selected) null else palette.line)
            button.setTextColor(if (selected) Color.WHITE else palette.ink)
            button.isSelected = selected
        }
        renderEntries()
    }

    private fun filterButton(key: String, title: String) = Button(this).apply {
        text = title
        tag = key
        isAllCaps = false
        textSize = 12f
        minHeight = dp(48)
        setPaddingRelative(dp(12), 0, dp(12), 0)
        background = rounded(if (activeFilter == key) palette.teal else palette.surface, dp(18), if (activeFilter == key) null else palette.line)
        setTextColor(if (activeFilter == key) Color.WHITE else palette.ink)
        setOnClickListener { setFilter(key) }
    }

    private fun chooseSort() {
        val labels = arrayOf(getString(R.string.sort_recently_updated), getString(R.string.sort_newest), getString(R.string.sort_oldest))
        val values = arrayOf("updated", "created", "oldest")
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.sort_button))
            .setSingleChoiceItems(labels, values.indexOf(sortMode).coerceAtLeast(0)) { dialog, which ->
                sortMode = values[which]
                AppPreferences.setSortMode(this, sortMode)
                dialog.dismiss()
                renderEntries()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun statCard(title: String, tint: Int, filter: String): Pair<View, TextView> {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = rounded(tint, dp(15))
            isClickable = true
            isFocusable = true
            contentDescription = getString(R.string.open_filter, title)
            setOnClickListener { setFilter(filter) }
        }
        val number = label("0", 20f, palette.ink, true)
        card.addView(number)
        card.addView(label(title, 12f, palette.muted).apply { setPadding(0, dp(3), 0, 0) })
        return card to number
    }

    private fun weightedParams(last: Boolean = false) = LinearLayout.LayoutParams(0, dp(48), 1f).apply { if (!last) marginEnd = dp(7) }
    private fun weightedStatParams(last: Boolean = false) = LinearLayout.LayoutParams(0, -2, 1f).apply { if (!last) marginEnd = dp(7) }

    private fun updatePopupPanel() {
        if (!::popupCard.isInitialized) return
        val permission = android.provider.Settings.canDrawOverlays(this)
        val enabled = AppPreferences.popupEnabled(this)
        popupStatus.text = when {
            enabled && permission -> getString(R.string.popup_active)
            !permission -> getString(R.string.popup_permission_missing)
            else -> getString(R.string.popup_disabled)
        }
        popupAction.text = if (enabled && permission) getString(R.string.popup_preview) else getString(R.string.popup_enable)
        popupCard.visibility = if (enabled && permission) View.GONE else View.VISIBLE
    }

    private fun onPopupAction() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun openOverlayPermission() {
        AppLockSession.markExternalIntentLaunch()
        try { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        catch (_: Exception) { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
    }

    private fun ensurePopupService() {
        try {
            val intent = Intent(this, QuickCaptureService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        } catch (_: Exception) {
            // The feature remains available through the widget and launcher shortcuts.
        }
    }

    private fun openAppSettings() {
        AppLockSession.markExternalIntentLaunch()
        startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun openNotificationSettings() {
        AppLockSession.markExternalIntentLaunch()
        val settings = if (Build.VERSION.SDK_INT >= 26) Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
        else Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        startActivity(settings)
    }

    private fun currentSettingsFingerprint() = "${AppPreferences.selectedLanguage(this)}:${AppPreferences.themeMode(this)}"

    private fun displayEntryText(entry: Entry): String = entry.title?.takeIf(String::isNotBlank) ?: when (entry.type) {
        "voice" -> getString(R.string.item_voice)
        else -> entry.content
    }

    private fun formatDate(at: Long): String = DateUtils.formatDateTime(
        this,
        at,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH
    )

    private fun scheduleDraftPersistence() {
        mainHandler.removeCallbacks(persistDraft)
        mainHandler.postDelayed(persistDraft, DRAFT_SAVE_DELAY_MS)
    }

    private fun imageButton(icon: Int, description: Int) = ImageButton(this).apply {
        setImageResource(icon)
        setColorFilter(palette.teal)
        contentDescription = getString(description)
        background = rounded(palette.surface, dp(13), palette.line)
        minimumWidth = dp(48)
        minimumHeight = dp(48)
        scaleType = ImageView.ScaleType.CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        isFocusable = true
    }

    private fun label(value: CharSequence, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun rounded(fill: Int, radius: Int, stroke: Int? = null): Drawable {
        val shape = GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius.toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }
        return RippleDrawable(android.content.res.ColorStateList.valueOf(ColorUtils.setAlphaComponent(palette.teal, 28)), shape, null)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        mainHandler.removeCallbacks(debounceSearch)
        mainHandler.removeCallbacks(recordTicker)
        undoRunnable?.let(undoHandler::removeCallbacks)
        if (recording) stopRecording(save = true)
        VoiceTranscriber.cancel(this)
        transcriptionDialog?.dismiss()
        transcriptionDialog = null
        player?.release(); player = null
        undoEntry?.let(::finalizeTrash)
        if (::db.isInitialized) ioExecutor.execute { db.close() }
        ioExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_START_VOICE_NOTE = "com.quicknote.app.START_VOICE_NOTE"
        const val EXTRA_CAPTURE_TYPE = "com.quicknote.app.CAPTURE_TYPE"
        const val EXTRA_OPEN_ENTRY_ID = "com.quicknote.app.OPEN_ENTRY_ID"
        private const val STATE_DRAFT = "draft_text"
        private const val STATE_QUERY = "search_query"
        private const val STATE_TYPE = "selected_type"
        private const val STATE_FILTER = "active_filter"
        private const val STATE_SORT = "sort_mode"
        private const val PREF_AUDIO_REQUESTED = "audio_permission_requested"
        private const val PREF_DRAFT_TEXT = "capture_draft_text"
        private const val PREF_DRAFT_TYPE = "capture_draft_type"
        private const val PREF_SEARCH_QUERY = "saved_search_query"
        private const val DRAFT_SAVE_DELAY_MS = 350L
        private const val MAX_SEARCH_LENGTH = 500
        private const val MAX_ENTRY_TEXT = 20_000
        private const val MAX_TITLE_LENGTH = 120
        private const val SEARCH_DEBOUNCE_MS = 220L
        private const val MAX_RECORDING_MS = 60 * 60 * 1000L
        private const val MIN_RECORDING_SPACE_BYTES = 10L * 1024 * 1024
        private const val ORPHAN_AUDIO_GRACE_MS = 60 * 60 * 1000L
        private const val TRASH_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
        private const val UNDO_WINDOW_MS = 5_000L
        private const val MENU_EDIT = 1
        private const val MENU_COPY = 2
        private const val MENU_SHARE = 3
        private const val MENU_DUPLICATE = 4
        private const val MENU_PIN = 5
        private const val MENU_TAG = 6
        private const val MENU_REMINDER = 7
        private const val MENU_PLAY = 8
        private const val MENU_TRANSCRIBE = 9
        private const val MENU_ARCHIVE = 10
        private const val MENU_DELETE = 11
        private const val MENU_RESTORE = 12
        private const val MENU_PERMANENT_DELETE = 13
    }
}
