package com.hellfire.ass.security

import android.content.Context

object AppLock {
    private const val PREFS = "app-lock"
    fun enabled(context: Context): Boolean = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false)
    } catch (caught: Exception) { com.hellfire.ass.Diagnostics.warn(caught); true }

    fun setEnabled(context: Context, enabled: Boolean) {
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).commit()) {
            "Could not save app lock preference"
        }
    }
}

/** Authentication is deliberately not saved across activity recreation or backgrounding. */
class AppLockSession {
    var authenticated = false
        private set
    fun authenticate() { authenticated = true }
    fun lock() { authenticated = false }
    fun requiresUnlock(enabled: Boolean) = enabled && !authenticated
}

/** Guard transitions always request a fresh strong biometric, independently of the app UI lock preference. */
fun AppLock.confirmGuard(
    activity: androidx.activity.ComponentActivity,
    onSuccess: () -> Unit,
    onError: (String) -> Unit,
): android.os.CancellationSignal {
    val signal = android.os.CancellationSignal()
    val builder = android.hardware.biometrics.BiometricPrompt.Builder(activity)
        .setTitle("Confirm TheftGuard change")
        .setDescription("Use an enrolled strong biometric to arm, resume or disarm this device.")
        .setNegativeButton("Cancel", activity.mainExecutor) { _, _ -> onError("Authentication cancelled") }
    if (android.os.Build.VERSION.SDK_INT >= 30) builder.setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG)
    try {
        builder.build().authenticate(signal, activity.mainExecutor, object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult) {
                if (!signal.isCanceled && activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) onSuccess()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { onError("Biometric cancelled or unavailable. Enroll a strong biometric in Android Settings.") }
        })
    } catch (_: Exception) { activity.mainExecutor.execute { onError("Strong biometric authentication is unavailable") } }
    return signal
}
