package com.quicknote.app

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ServiceCompat

class QuickCaptureService : Service() {
    private lateinit var db: EntryDb
    private lateinit var windowManager: WindowManager
    private var popupView: View? = null
    private var editor: EditText? = null
    private var noteTypeButton: Button? = null
    private var taskTypeButton: Button? = null
    private var selectedType = "note"
    private val handler = Handler(Looper.getMainLooper())
    private val palette get() = AppPalette.from(this)
    private val INK get() = palette.ink
    private val MUTED get() = palette.muted
    private val TEAL get() = palette.teal
    private val SOFT get() = palette.soft

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_USER_PRESENT && AppPreferences.popupEnabled(this@QuickCaptureService)) {
                handler.postDelayed({ showPopup() }, UNLOCK_SETTLE_DELAY_MS)
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        db = EntryDb(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        NotificationHelper.createChannels(this)
        val notification = NotificationHelper.serviceNotification(this)
        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(this, SERVICE_NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(SERVICE_NOTIFICATION_ID, notification)
        }
        val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
        if (Build.VERSION.SDK_INT >= 33) {
            // Android system/privileged components may send this event from a UID other than system_server.
            // NOT_EXPORTED can silently filter those legitimate system broadcasts.
            registerReceiver(unlockReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(unlockReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SHOW_POPUP && AppPreferences.popupEnabled(this)) {
            handler.postDelayed({ showPopup() }, PREVIEW_SETTLE_DELAY_MS)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showPopup() {
        if (!AppPreferences.popupEnabled(this) || !Settings.canDrawOverlays(this)) return
        hidePopup()
        selectedType = "note"
        val direction = resources.configuration.layoutDirection
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = direction
            setPadding(dp(18), dp(16), dp(18), dp(17))
            background = rounded(palette.surface, dp(23), palette.line)
            elevation = dp(12).toFloat()
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = direction }
        val heading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heading.addView(text(getString(R.string.overlay_title), 18f, INK, true))
        heading.addView(text(getString(R.string.overlay_subtitle), 12f, MUTED).apply { setPadding(0, dp(3), 0, 0) })
        top.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(text("×", 25f, MUTED, true).apply {
            gravity = Gravity.CENTER
            contentDescription = getString(R.string.overlay_close)
            setOnClickListener { hidePopup() }
        }, LinearLayout.LayoutParams(dp(38), dp(40)))
        card.addView(top)

        val input = EditText(this).apply {
            hint = getString(R.string.overlay_note_hint)
            textSize = 16f
            minLines = 3
            maxLines = 5
            gravity = Gravity.TOP or Gravity.START
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            setTextColor(INK)
            setHintTextColor(palette.hint)
            setPadding(dp(13), dp(11), dp(13), dp(11))
            background = rounded(palette.field, dp(15), palette.line)
        }
        editor = input
        card.addView(input, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(13) })

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = direction }
        val note = actionButton(getString(R.string.type_note)) { chooseType("note") }
        val task = actionButton(getString(R.string.type_task)) { chooseType("task") }
        val voice = actionButton(getString(R.string.type_voice)) { openVoiceCapture() }
        noteTypeButton = note
        taskTypeButton = task
        actions.addView(note, weightParams())
        actions.addView(task, weightParams())
        actions.addView(voice, weightParams(last = true))
        card.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        val save = Button(this).apply {
            text = getString(R.string.overlay_save)
            isAllCaps = false
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = rounded(TEAL, dp(14))
            setOnClickListener { saveCapture() }
        }
        card.addView(save, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(11) })

        popupView = card
        val params = LayoutParams(
            resources.displayMetrics.widthPixels - dp(24),
            LayoutParams.WRAP_CONTENT,
            LayoutParams.TYPE_APPLICATION_OVERLAY,
            LayoutParams.FLAG_LAYOUT_IN_SCREEN or LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(76)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        try {
            windowManager.addView(card, params)
            input.requestFocus()
        } catch (_: Exception) {
            popupView = null
            Toast.makeText(this, getString(R.string.overlay_permission_toast), Toast.LENGTH_LONG).show()
        }
    }

    private fun chooseType(type: String) {
        selectedType = type
        editor?.hint = getString(if (type == "task") R.string.overlay_task_hint else R.string.overlay_note_hint)
        noteTypeButton?.apply {
            setTextColor(if (type == "note") Color.WHITE else INK)
            background = rounded(if (type == "note") TEAL else SOFT, dp(12))
        }
        taskTypeButton?.apply {
            setTextColor(if (type == "task") Color.WHITE else INK)
            background = rounded(if (type == "task") TEAL else SOFT, dp(12))
        }
    }

    private fun saveCapture() {
        val textValue = editor?.text?.toString()?.trim().orEmpty()
        if (textValue.isBlank()) { editor?.error = getString(R.string.overlay_error); return }
        val id = db.add(selectedType, textValue)
        db.all().firstOrNull { it.id == id }?.let { NotificationHelper.showItem(this, it) }
        hidePopup()
        Toast.makeText(this, getString(if (selectedType == "task") R.string.overlay_saved_task else R.string.overlay_saved_note), Toast.LENGTH_SHORT).show()
    }

    private fun openVoiceCapture() {
        hidePopup()
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_START_VOICE_NOTE, true)
        }
        startActivity(intent)
    }

    private fun hidePopup() {
        val view = popupView ?: return
        try { windowManager.removeView(view) } catch (_: Exception) { }
        popupView = null
        editor = null
        noteTypeButton = null
        taskTypeButton = null
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun actionButton(title: String, onClick: () -> Unit) = Button(this).apply {
        text = title; isAllCaps = false; textSize = 12f; minHeight = dp(42); minimumHeight = dp(42)
        setPadding(dp(2), 0, dp(2), 0); setTextColor(INK); background = rounded(SOFT, dp(12)); setOnClickListener { onClick() }
    }
    private fun weightParams(last: Boolean = false) = LinearLayout.LayoutParams(0, dp(43), 1f).apply { if (!last) marginEnd = dp(7) }
    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply { text = value; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD) }
    private fun rounded(fill: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply { setColor(fill); cornerRadius = radius.toFloat(); if (stroke != null) setStroke(dp(1), stroke) }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        hidePopup()
        try { unregisterReceiver(unlockReceiver) } catch (_: Exception) { }
        db.close()
        super.onDestroy()
    }

    companion object {
        const val ACTION_SHOW_POPUP = "com.quicknote.app.SHOW_POPUP"
        const val SERVICE_NOTIFICATION_ID = 6001
        private const val UNLOCK_SETTLE_DELAY_MS = 650L
        private const val PREVIEW_SETTLE_DELAY_MS = 200L
    }
}
