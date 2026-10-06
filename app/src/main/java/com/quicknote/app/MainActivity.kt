package com.quicknote.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.content.Context
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
    private var selectedType = "note"
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var player: MediaPlayer? = null
    private var recording = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(246, 247, 244)
        window.navigationBarColor = Color.rgb(246, 247, 244)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        db = EntryDb(this)
        NotificationHelper.createChannels(this)
        requestNotificationPermissionIfNeeded()
        buildScreen()
        refreshEntries()
    }

    private fun buildScreen() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(18))
            setBackgroundColor(Color.rgb(246, 247, 244))
        }
        val title = TextView(this).apply {
            text = "QuickNote"
            textSize = 29f
            setTextColor(Color.rgb(29, 45, 40))
            setTypeface(typeface, Typeface.BOLD)
        }
        page.addView(title)
        page.addView(TextView(this).apply {
            text = "Catch it before it slips away."
            textSize = 14f
            setTextColor(Color.rgb(105, 117, 111))
            setPadding(0, dp(3), 0, dp(19))
        })

        val capture = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(15))
            background = rounded(Color.WHITE, dp(18), Color.rgb(228, 233, 229))
        }
        capture.addView(TextView(this).apply {
            text = "QUICK CAPTURE"
            textSize = 11f
            letterSpacing = .08f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(23, 107, 91))
        })
        editor = EditText(this).apply {
            hint = "Write a note or a to-do…"
            textSize = 16f
            minLines = 3
            maxLines = 5
            gravity = Gravity.TOP or Gravity.START
            setTextColor(Color.rgb(35, 45, 41))
            setHintTextColor(Color.rgb(151, 160, 155))
            setPadding(0, dp(10), 0, dp(10))
            background = null
        }
        capture.addView(editor, LinearLayout.LayoutParams(-1, -2))
        val typeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        noteButton = typeButton("Note") { selectType("note") }
        taskButton = typeButton("To-do") { selectType("task") }
        voiceButton = typeButton("Voice note") { toggleRecording() }
        typeRow.addView(noteButton, weightedParams())
        typeRow.addView(taskButton, weightedParams())
        typeRow.addView(voiceButton, weightedParams())
        capture.addView(typeRow)
        val saveButton = Button(this).apply {
            text = "Save note"
            isAllCaps = false
            textSize = 15f
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(23, 107, 91), dp(13))
            setOnClickListener { saveTextEntry() }
        }
        val saveParams = LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(12) }
        capture.addView(saveButton, saveParams)
        page.addView(capture)

        val savedHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(22), 0, dp(10))
        }
        savedHeader.addView(TextView(this).apply {
            text = "Your saved items"
            textSize = 19f
            setTextColor(Color.rgb(29, 45, 40))
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        savedHeader.addView(TextView(this).apply {
            text = "On this device"
            textSize = 12f
            setTextColor(Color.rgb(119, 130, 124))
        })
        page.addView(savedHeader)
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            clipToPadding = false
            addView(page, ViewGroup.LayoutParams(-1, -2))
        }
        // The list is appended after the scroll view is built so the capture panel remains the first focus.
        page.addView(listContainer)
        setContentView(scroll)
        selectType("note")
    }

    private fun typeButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 12f
        minHeight = dp(44)
        minimumHeight = dp(44)
        setPadding(dp(3), 0, dp(3), 0)
        setOnClickListener { action() }
    }

    private fun weightedParams() = LinearLayout.LayoutParams(0, dp(46), 1f).apply {
        marginEnd = dp(5)
    }

    private fun selectType(type: String) {
        selectedType = type
        noteButton.setTextColor(if (type == "note") Color.WHITE else Color.rgb(63, 79, 71))
        taskButton.setTextColor(if (type == "task") Color.WHITE else Color.rgb(63, 79, 71))
        noteButton.background = rounded(if (type == "note") Color.rgb(23, 107, 91) else Color.rgb(239, 243, 239), dp(11))
        taskButton.background = rounded(if (type == "task") Color.rgb(23, 107, 91) else Color.rgb(239, 243, 239), dp(11))
        editor.hint = if (type == "task") "What do you need to do?" else "Write a note…"
    }

    private fun saveTextEntry() {
        val text = editor.text.toString().trim()
        if (text.isEmpty()) {
            editor.error = "Type something first"
            editor.requestFocus()
            return
        }
        val id = db.add(selectedType, text)
        val entry = db.all().first { it.id == id }
        NotificationHelper.showItem(this, entry)
        editor.text.clear()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(editor.windowToken, 0)
        Toast.makeText(this, if (selectedType == "task") "To-do saved" else "Note saved", Toast.LENGTH_SHORT).show()
        refreshEntries()
    }

    private fun toggleRecording() {
        if (recording) {
            stopRecording(save = true)
        } else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
        }
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
            activeRecorder.prepare()
            activeRecorder.start()
            recorder = activeRecorder
            recordingFile = file
            recording = true
            voiceButton.text = "Stop & save"
            voiceButton.background = rounded(Color.rgb(184, 68, 56), dp(11))
            voiceButton.setTextColor(Color.WHITE)
            Toast.makeText(this, "Recording… tap Stop & save when finished", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            recorder?.release()
            recorder = null
            recordingFile?.delete()
            recordingFile = null
            recording = false
            Toast.makeText(this, "Could not start recording: ${e.localizedMessage ?: "check microphone permission"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopRecording(save: Boolean) {
        val file = recordingFile
        try {
            recorder?.stop()
        } catch (_: Exception) {
            file?.delete()
        } finally {
            recorder?.release()
            recorder = null
            recording = false
            recordingFile = null
            voiceButton.text = "Voice note"
            voiceButton.setTextColor(Color.rgb(63, 79, 71))
            voiceButton.background = rounded(Color.rgb(239, 243, 239), dp(11))
        }
        if (save && file != null && file.exists() && file.length() > 0) {
            val label = "Voice note · " + SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date())
            val id = db.add("voice", label, file.absolutePath)
            db.all().firstOrNull { it.id == id }?.let { NotificationHelper.showItem(this, it) }
            Toast.makeText(this, "Voice note saved", Toast.LENGTH_SHORT).show()
            refreshEntries()
        }
    }

    private fun refreshEntries() {
        if (!::listContainer.isInitialized) return
        listContainer.removeAllViews()
        val items = db.all()
        items.forEach { NotificationHelper.showItem(this, it) }
        if (items.isEmpty()) {
            val empty = TextView(this).apply {
                text = "Your notes and reminders will appear here."
                textSize = 14f
                setTextColor(Color.rgb(117, 128, 122))
                setPadding(dp(15), dp(18), dp(15), dp(18))
                background = rounded(Color.WHITE, dp(14), Color.rgb(228, 233, 229))
            }
            listContainer.addView(empty)
            return
        }
        items.forEach { entry ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(13), dp(10), dp(9), dp(10))
                background = rounded(Color.WHITE, dp(14), Color.rgb(228, 233, 229))
            }
            val marker = if (entry.type == "task") "□" else if (entry.type == "voice") "♫" else "●"
            if (entry.type == "task") {
                val checkbox = CheckBox(this).apply {
                    isChecked = entry.done
                    buttonTintList = android.content.res.ColorStateList.valueOf(Color.rgb(23, 107, 91))
                    setOnCheckedChangeListener { _, checked ->
                        db.setDone(entry.id, checked)
                        val latest = db.all().firstOrNull { it.id == entry.id }
                        if (latest != null) NotificationHelper.showItem(this@MainActivity, latest)
                        refreshEntries()
                    }
                }
                card.addView(checkbox)
            } else {
                card.addView(TextView(this).apply {
                    text = marker
                    textSize = 19f
                    setTextColor(Color.rgb(23, 107, 91))
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(dp(30), -2))
            }
            val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            details.addView(TextView(this).apply {
                text = entry.content
                textSize = 15f
                setTextColor(if (entry.done) Color.rgb(141, 150, 144) else Color.rgb(35, 45, 41))
                if (entry.done) paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
            })
            details.addView(TextView(this).apply {
                val label = when (entry.type) { "task" -> "TO-DO"; "voice" -> "VOICE NOTE"; else -> "NOTE" }
                text = "$label · ${SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(entry.createdAt))}"
                textSize = 10f
                setTextColor(Color.rgb(121, 132, 125))
                setPadding(0, dp(4), 0, 0)
            })
            if (entry.type == "voice") {
                details.setOnClickListener { playVoice(entry) }
                details.isClickable = true
            }
            card.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
            val delete = TextView(this).apply {
                text = "×"
                textSize = 24f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(125, 136, 129))
                setPadding(dp(10), 0, dp(5), 0)
                contentDescription = "Delete item"
                setOnClickListener {
                    val removed = db.delete(entry.id)
                    NotificationHelper.removeItem(this@MainActivity, entry.id)
                    removed?.audioPath?.let { path -> File(path).delete() }
                    refreshEntries()
                }
            }
            card.addView(delete, LinearLayout.LayoutParams(dp(36), dp(46)))
            val params = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) }
            listContainer.addView(card, params)
        }
    }

    private fun playVoice(entry: Entry) {
        val path = entry.audioPath ?: return
        try {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(path)
                setOnCompletionListener { it.release(); if (player === it) player = null }
                prepare()
                start()
            }
            Toast.makeText(this, "Playing voice note", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Recording could not be played", Toast.LENGTH_SHORT).show()
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
        if (recording) stopRecording(save = false)
        player?.release()
        player = null
        db.close()
        super.onDestroy()
    }

    private fun rounded(fill: Int, radius: Int, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radius.toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_AUDIO = 401
        private const val REQ_NOTIFICATIONS = 402
    }
}
