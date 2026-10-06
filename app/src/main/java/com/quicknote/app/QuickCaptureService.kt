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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class QuickCaptureService : Service() {
    private lateinit var db: EntryDb
    private lateinit var windowManager: WindowManager
    private var popupView: View? = null
    private var editor: EditText? = null
    private var noteTypeButton: Button? = null
    private var taskTypeButton: Button? = null
    private var selectedType = "note"
    private val handler = Handler(Looper.getMainLooper())

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_USER_PRESENT) handler.postDelayed({ showPopup() }, 320)
        }
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
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(unlockReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(unlockReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SHOW_POPUP) handler.post { showPopup() }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showPopup() {
        if (!Settings.canDrawOverlays(this)) return
        hidePopup()
        selectedType = "note"
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(18), dp(16), dp(18), dp(17))
            background = rounded(Color.WHITE, dp(23), Color.rgb(224, 234, 227))
            elevation = dp(12).toFloat()
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val heading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heading.addView(text("التقطها بسرعة", 18f, Color.rgb(35, 51, 45), true))
        heading.addView(text("اكتبها قبل ما تنساها", 12f, Color.rgb(115, 132, 123)).apply { setPadding(0, dp(3), 0, 0) })
        top.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        val close = text("×", 25f, Color.rgb(104, 120, 111), true).apply {
            gravity = Gravity.CENTER
            contentDescription = "إغلاق"
            setOnClickListener { hidePopup() }
        }
        top.addView(close, LinearLayout.LayoutParams(dp(38), dp(40)))
        card.addView(top)

        val input = EditText(this).apply {
            hint = "اكتب ملاحظة أو مهمة…"
            textSize = 16f
            minLines = 3
            maxLines = 5
            gravity = Gravity.TOP or Gravity.START
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG_RTL
            setTextColor(Color.rgb(35, 51, 45))
            setHintTextColor(Color.rgb(146, 159, 151))
            setPadding(dp(13), dp(11), dp(13), dp(11))
            background = rounded(Color.rgb(246, 249, 246), dp(15), Color.rgb(234, 239, 235))
        }
        editor = input
        card.addView(input, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(13) })

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val note = actionButton("ملاحظة") { chooseType("note") }
        val task = actionButton("مهمة") { chooseType("task") }
        val voice = actionButton("صوت") { openVoiceCapture() }
        noteTypeButton = note
        taskTypeButton = task
        actions.addView(note, weightParams())
        actions.addView(task, weightParams())
        actions.addView(voice, weightParams(last = true))
        card.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        val save = Button(this).apply {
            text = "حفظ"
            isAllCaps = false
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(23, 107, 91), dp(14))
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
            softInputMode = android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        try {
            windowManager.addView(card, params)
            input.requestFocus()
        } catch (_: Exception) {
            popupView = null
            Toast.makeText(this, "تأكد من تفعيل إذن الظهور فوق التطبيقات", Toast.LENGTH_LONG).show()
        }
    }

    private fun chooseType(type: String) {
        selectedType = type
        editor?.hint = if (type == "task") "إيه المهمة اللي عايز تفتكرها؟" else "اكتب ملاحظتك…"
        noteTypeButton?.apply {
            setTextColor(if (type == "note") Color.WHITE else Color.rgb(55, 77, 66))
            background = rounded(if (type == "note") Color.rgb(23, 107, 91) else Color.rgb(239, 245, 240), dp(12))
        }
        taskTypeButton?.apply {
            setTextColor(if (type == "task") Color.WHITE else Color.rgb(55, 77, 66))
            background = rounded(if (type == "task") Color.rgb(23, 107, 91) else Color.rgb(239, 245, 240), dp(12))
        }
    }

    private fun saveCapture() {
        val textValue = editor?.text?.toString()?.trim().orEmpty()
        if (textValue.isBlank()) {
            editor?.error = "اكتب حاجة الأول"
            return
        }
        val id = db.add(selectedType, textValue)
        db.all().firstOrNull { it.id == id }?.let { NotificationHelper.showItem(this, it) }
        hidePopup()
        Toast.makeText(this, if (selectedType == "task") "اتحفظت المهمة" else "اتحفظت الملاحظة", Toast.LENGTH_SHORT).show()
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
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun actionButton(title: String, onClick: () -> Unit) = Button(this).apply {
        text = title
        isAllCaps = false
        textSize = 12f
        minHeight = dp(42)
        minimumHeight = dp(42)
        setPadding(dp(2), 0, dp(2), 0)
        setTextColor(Color.rgb(55, 77, 66))
        background = rounded(Color.rgb(239, 245, 240), dp(12))
        setOnClickListener { onClick() }
    }

    private fun weightParams(last: Boolean = false) = LinearLayout.LayoutParams(0, dp(43), 1f).apply {
        if (!last) marginEnd = dp(7)
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun rounded(fill: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill); cornerRadius = radius.toFloat(); if (stroke != null) setStroke(dp(1), stroke)
    }

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
    }
}
