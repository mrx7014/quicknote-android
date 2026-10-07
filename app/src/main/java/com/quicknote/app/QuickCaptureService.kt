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
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ServiceCompat
import androidx.core.graphics.ColorUtils
import java.util.concurrent.Executors

class QuickCaptureService : Service() {
    private lateinit var windowManager: WindowManager
    private var popupView: View? = null
    private var popupParams: LayoutParams? = null
    private var editor: EditText? = null
    private var noteTypeButton: Button? = null
    private var taskTypeButton: Button? = null
    private var selectedType = "note"
    private val handler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val palette get() = AppPalette.from(this)
    private val INK get() = palette.ink
    private val MUTED get() = palette.muted
    private val TEAL get() = palette.teal
    private val SOFT get() = palette.soft
    private val autoDismiss = Runnable { hidePopup() }
    private val showAfterUnlock = Runnable {
        if (AppPreferences.markUnlockPopupAllowed(this)) showPopup()
    }

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_USER_PRESENT && AppPreferences.popupEnabled(this@QuickCaptureService)) {
                handler.removeCallbacks(showAfterUnlock)
                handler.postDelayed(showAfterUnlock, UNLOCK_SETTLE_DELAY_MS)
            }
        }
    }

    private val configurationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_CONFIGURATION_CHANGED) updatePopupPosition()
        }
    }

    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(AppLocale.wrap(newBase)) }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        NotificationHelper.createChannels(this)
        val notification = NotificationHelper.serviceNotification(this)
        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(this, SERVICE_NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else startForeground(SERVICE_NOTIFICATION_ID, notification)
        val unlockFilter = IntentFilter(Intent.ACTION_USER_PRESENT)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(unlockReceiver, unlockFilter, Context.RECEIVER_NOT_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            registerReceiver(unlockReceiver, unlockFilter)
        }
        val configFilter = IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(configurationReceiver, configFilter, Context.RECEIVER_NOT_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            registerReceiver(configurationReceiver, configFilter)
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
            setPadding(dp(17), dp(14), dp(17), dp(15))
            background = rounded(palette.surface, dp(23), palette.line)
            elevation = dp(12).toFloat()
            isFocusable = true
            isFocusableInTouchMode = true
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) { hidePopup(); true } else false
            }
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) { hidePopup(); true } else false
            }
        }

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = direction }
        val heading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heading.addView(text(getString(R.string.overlay_title), 18f, INK, true))
        heading.addView(text(getString(R.string.overlay_subtitle), 12f, MUTED).apply { setPadding(0, dp(3), 0, 0) })
        top.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        val close = ImageButton(this).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(MUTED)
            contentDescription = getString(R.string.overlay_close)
            minimumWidth = dp(48); minimumHeight = dp(48)
            background = ripple(palette.surface)
            setOnClickListener { hidePopup() }
        }
        top.addView(close, LinearLayout.LayoutParams(dp(48), dp(48)))
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
            setPaddingRelative(dp(13), dp(11), dp(13), dp(11))
            background = rounded(palette.field, dp(15), palette.line)
            setOnFocusChangeListener { _, focused -> if (focused) resetTimeout() }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = resetTimeout()
                override fun afterTextChanged(s: android.text.Editable?) = Unit
            })
        }
        editor = input
        card.addView(input, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(11) })

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = direction }
        noteTypeButton = actionButton(getString(R.string.type_note)) { chooseType("note") }
        taskTypeButton = actionButton(getString(R.string.type_task)) { chooseType("task") }
        val voice = actionButton(getString(R.string.type_voice)) { openVoiceCapture() }
        actions.addView(noteTypeButton, weightParams())
        actions.addView(taskTypeButton, weightParams())
        actions.addView(voice, weightParams(last = true))
        card.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        chooseType("note")

        val save = Button(this).apply {
            text = getString(R.string.overlay_save)
            isAllCaps = false
            textSize = 15f
            minHeight = dp(48)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = ripple(TEAL)
            setOnClickListener { saveCapture(this) }
        }
        card.addView(save, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(9) })

        val params = LayoutParams(
            popupWidth(),
            LayoutParams.WRAP_CONTENT,
            LayoutParams.TYPE_APPLICATION_OVERLAY,
            LayoutParams.FLAG_LAYOUT_IN_SCREEN or LayoutParams.FLAG_NOT_TOUCH_MODAL or LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = safeTopOffset()
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
        }
        popupView = card
        popupParams = params
        try {
            windowManager.addView(card, params)
            card.requestFocus()
            input.requestFocus()
            resetTimeout()
        } catch (_: Exception) {
            popupView = null
            popupParams = null
            Toast.makeText(this, getString(R.string.overlay_permission_toast), Toast.LENGTH_LONG).show()
        }
    }

    private fun popupWidth(): Int {
        val screen = if (Build.VERSION.SDK_INT >= 30) windowManager.currentWindowMetrics.bounds.width() else resources.displayMetrics.widthPixels
        return (screen - dp(24)).coerceAtMost(dp(460)).coerceAtLeast(dp(280))
    }

    private fun safeTopOffset(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        val status = if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
        return status + dp(12)
    }

    private fun updatePopupPosition() {
        val view = popupView ?: return
        val params = popupParams ?: return
        params.width = popupWidth()
        params.y = safeTopOffset()
        try { windowManager.updateViewLayout(view, params) } catch (_: Exception) { hidePopup() }
    }

    private fun resetTimeout() {
        handler.removeCallbacks(autoDismiss)
        if (popupView != null) handler.postDelayed(autoDismiss, POPUP_IDLE_TIMEOUT_MS)
    }

    private fun chooseType(type: String) {
        selectedType = type
        editor?.hint = getString(if (type == "task") R.string.overlay_task_hint else R.string.overlay_note_hint)
        noteTypeButton?.apply {
            setTextColor(if (type == "note") Color.WHITE else INK)
            background = ripple(if (type == "note") TEAL else SOFT)
            isSelected = type == "note"
        }
        taskTypeButton?.apply {
            setTextColor(if (type == "task") Color.WHITE else INK)
            background = ripple(if (type == "task") TEAL else SOFT)
            isSelected = type == "task"
        }
    }

    private fun saveCapture(button: Button) {
        val textValue = editor?.text?.toString()?.trim()?.take(MAX_CAPTURE_LENGTH).orEmpty()
        if (textValue.isBlank()) { editor?.error = getString(R.string.overlay_error); return }
        val type = selectedType
        button.isEnabled = false
        ioExecutor.execute {
            try {
                val saved = EntryDb(applicationContext).use { database ->
                    val id = database.add(type, textValue)
                    database.get(id)
                }
                saved?.let { NotificationHelper.showItem(this, it) }
                QuickNoteWidgetProvider.refreshAll(this)
                handler.post {
                    hidePopup()
                    Toast.makeText(this, getString(if (type == "task") R.string.overlay_saved_task else R.string.overlay_saved_note), Toast.LENGTH_SHORT).show()
                }
            } catch (error: Exception) {
                android.util.Log.e("QuickNotePopup", "Could not save capture", error)
                handler.post { button.isEnabled = true; Toast.makeText(this, getString(R.string.overlay_save_failed), Toast.LENGTH_LONG).show() }
            }
        }
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
        handler.removeCallbacks(autoDismiss)
        val view = popupView
        if (view != null) {
            try { windowManager.removeView(view) } catch (_: Exception) { }
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(view.windowToken, 0)
        }
        popupView = null
        popupParams = null
        editor = null
        noteTypeButton = null
        taskTypeButton = null
    }

    private fun actionButton(title: String, onClick: () -> Unit) = Button(this).apply {
        text = title
        isAllCaps = false
        textSize = 12f
        minHeight = dp(48)
        minimumHeight = dp(48)
        setPaddingRelative(dp(4), 0, dp(4), 0)
        setTextColor(INK)
        background = ripple(SOFT)
        contentDescription = title
        setOnClickListener { onClick() }
    }

    private fun weightParams(last: Boolean = false) = LinearLayout.LayoutParams(0, dp(48), 1f).apply { if (!last) marginEnd = dp(7) }
    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun rounded(fill: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill); cornerRadius = radius.toFloat(); if (stroke != null) setStroke(dp(1), stroke)
    }
    private fun ripple(fill: Int) = RippleDrawable(android.content.res.ColorStateList.valueOf(ColorUtils.setAlphaComponent(palette.teal, 30)), rounded(fill, dp(13)), null)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        hidePopup()
        try { unregisterReceiver(unlockReceiver) } catch (_: Exception) { }
        try { unregisterReceiver(configurationReceiver) } catch (_: Exception) { }
        ioExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        const val ACTION_SHOW_POPUP = "com.quicknote.app.SHOW_POPUP"
        const val SERVICE_NOTIFICATION_ID = 6001
        private const val UNLOCK_SETTLE_DELAY_MS = 650L
        private const val PREVIEW_SETTLE_DELAY_MS = 200L
        private const val POPUP_IDLE_TIMEOUT_MS = 90_000L
        private const val MAX_CAPTURE_LENGTH = 20_000
    }
}
