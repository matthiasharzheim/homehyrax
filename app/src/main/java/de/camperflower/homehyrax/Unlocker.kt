package de.camperflower.homehyrax

import android.app.Activity
import android.app.KeyguardManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Entsperren per Fingerabdruck, Gesicht oder Geraete-PIN - fuer Web-Apps und
 * Schaltflaechen. Im onCreate anlegen (registriert einen Activity-Result-Launcher).
 */
class Unlocker(private val act: FragmentActivity) {
    private var legacyResult: ((Boolean) -> Unit)? = null
    private val legacy = act.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        legacyResult?.invoke(r.resultCode == Activity.RESULT_OK)
        legacyResult = null
    }

    private val allowed: Int
        get() = if (Build.VERSION.SDK_INT >= 30) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    /** false = keine Displaysperre eingerichtet */
    fun available(): Boolean =
        if (Build.VERSION.SDK_INT < 28) act.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
        else BiometricManager.from(act).canAuthenticate(allowed) == BiometricManager.BIOMETRIC_SUCCESS

    /**
     * @param onResult ok = entsperrt; ok=false + error=null = abgebrochen;
     *                 error != null = Problem (Text fuer die Anzeige)
     */
    fun ask(title: String, subtitle: String, onResult: (ok: Boolean, error: String?) -> Unit) {
        if (!available()) {
            onResult(false, act.getString(R.string.unlock_no_screen_lock))
            return
        }
        if (Build.VERSION.SDK_INT < 28) {
            // Android 8.x: Sperrbildschirm-Bestaetigung (kein AppCompat-Fingerprint-Dialog noetig)
            @Suppress("DEPRECATION")
            val i = act.getSystemService(KeyguardManager::class.java)?.createConfirmDeviceCredentialIntent(title, subtitle)
            if (i == null) { onResult(true, null); return }
            legacyResult = { ok -> onResult(ok, null) }
            legacy.launch(i)
            return
        }
        val prompt = BiometricPrompt(act, ContextCompat.getMainExecutor(act), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(true, null)
            override fun onAuthenticationError(code: Int, msg: CharSequence) {
                val cancelled = code == BiometricPrompt.ERROR_USER_CANCELED || code == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    code == BiometricPrompt.ERROR_CANCELED
                onResult(false, if (cancelled) null else msg.toString())
            }
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(allowed)
                .build()
        )
    }
}
