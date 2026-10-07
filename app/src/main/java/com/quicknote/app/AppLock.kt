package com.quicknote.app

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
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
    @Volatile private var lastBackgroundAt: Long = 0L
    @Volatile private var externalReturnPending: Boolean = false
    @Volatile private var externalLaunchAt: Long = 0L

    @Synchronized fun markExternalIntentLaunch() {
        externalReturnPending = true
        externalLaunchAt = SystemClock.elapsedRealtime()
    }

    @Synchronized fun shouldLock(context: Context): Boolean {
        if (!AppPreferences.appLockEnabled(context)) {
            authenticated = true
            externalReturnPending = false
            return false
        }
        if (!authenticated) return true
        val now = SystemClock.elapsedRealtime()
        val elapsed = if (lastBackgroundAt == 0L) 0L else now - lastBackgroundAt
        if (externalReturnPending) {
            externalReturnPending = false
            if (now - externalLaunchAt <= MAX_EXTERNAL_RETURN_GRACE_MS) {
                lastBackgroundAt = 0L
                return false
            }
        }
        if (elapsed >= AppPreferences.lockTimeoutMillis(context)) {
            authenticated = false
            lastBackgroundAt = 0L
            return true
        }
        lastBackgroundAt = 0L
        return false
    }

    @Synchronized fun noteBackground() {
        lastBackgroundAt = SystemClock.elapsedRealtime()
    }

    @Synchronized fun preserveAcrossConfigurationChange() {
        lastBackgroundAt = 0L
    }

    private const val MAX_EXTERNAL_RETURN_GRACE_MS = 5 * 60 * 1000L
}

class QuickNoteApplication : Application(), Application.ActivityLifecycleCallbacks {
    private var startedActivities = 0

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        if (startedActivities == 0 && activity.isChangingConfigurations) AppLockSession.preserveAcrossConfigurationChange()
        startedActivities++
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
        if (startedActivities == 0 && !activity.isChangingConfigurations) AppLockSession.noteBackground()
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

    /** A real, accessible touch barrier above protected content. */
    fun createCover(context: Context, retry: () -> Unit): View {
        val colors = AppPalette.from(context)
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
            setBackgroundColor(colors.page)
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = context.getString(R.string.lock_cover_title)
            setOnClickListener { /* consume touches; underlying protected controls must never receive them */ }
            addView(TextView(context).apply {
                text = context.getString(R.string.lock_cover_title)
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(colors.ink)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            addView(TextView(context).apply {
                text = context.getString(R.string.lock_cover_subtitle)
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(colors.muted)
                setPadding(0, dp(12), 0, dp(20))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            addView(Button(context).apply {
                text = context.getString(R.string.lock_retry)
                minHeight = dp(48)
                setOnClickListener { retry() }
            })
        }
    }
}
