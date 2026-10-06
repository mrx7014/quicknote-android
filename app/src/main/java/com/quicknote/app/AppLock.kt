package com.quicknote.app

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

object AppLockSession {
    @Volatile var authenticated: Boolean = false
}

class QuickNoteApplication : Application(), Application.ActivityLifecycleCallbacks {
    private val handler = Handler(Looper.getMainLooper())
    private var startedActivities = 0
    private val markBackground = Runnable { if (startedActivities == 0) AppLockSession.authenticated = false }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        startedActivities++
        handler.removeCallbacks(markBackground)
    }
    override fun onActivityStopped(activity: Activity) {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
        if (startedActivities == 0) handler.postDelayed(markBackground, 900)
    }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

object AppLockHelper {
    private const val AUTHENTICATORS_MODERN = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    private const val AUTHENTICATORS_OLD = BiometricManager.Authenticators.BIOMETRIC_WEAK

    fun supported(context: Context): Boolean {
        val authenticators = if (Build.VERSION.SDK_INT >= 30) AUTHENTICATORS_MODERN else AUTHENTICATORS_OLD
        return BiometricManager.from(context).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun authenticate(activity: FragmentActivity, onSuccess: () -> Unit, onFailure: (String) -> Unit) {
        val authenticators = if (Build.VERSION.SDK_INT >= 30) AUTHENTICATORS_MODERN else AUTHENTICATORS_OLD
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                AppLockSession.authenticated = true
                onSuccess()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onFailure(errString.toString())
            }
            override fun onAuthenticationFailed() = Unit
        })
        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.lock_prompt_title))
            .setSubtitle(activity.getString(R.string.lock_prompt_subtitle))
            .setAllowedAuthenticators(authenticators)
        if (Build.VERSION.SDK_INT < 30) builder.setNegativeButtonText(activity.getString(R.string.cancel))
        prompt.authenticate(builder.build())
    }

    fun createCover(context: Context, retry: () -> Unit): View {
        val colors = AppPalette.from(context)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(32, 32, 32, 32)
            setBackgroundColor(colors.page)
        }
        container.addView(TextView(context).apply {
            text = context.getString(R.string.lock_cover_title); textSize = 22f; gravity = Gravity.CENTER
            setTextColor(colors.ink); setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        container.addView(TextView(context).apply {
            text = context.getString(R.string.lock_cover_subtitle); textSize = 14f; gravity = Gravity.CENTER
            setTextColor(colors.muted); setPadding(0, 12, 0, 20)
        })
        container.addView(Button(context).apply {
            text = context.getString(R.string.lock_retry); setOnClickListener { retry() }
        })
        return container
    }
}
