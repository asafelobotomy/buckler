package dev.solon.bucklerhome

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Encrypted store for API tokens. The instance is created once and cached.
 *
 * If the Keystore is unusable (corrupt key, some devices) we delete the unreadable
 * encrypted file once and retry. Only if that also fails do we fall back to a *separate*
 * plain file, so a broken keystore can never mix plaintext into the encrypted file.
 */
object SecurePrefs {
    private const val TAG = "SecurePrefs"
    private const val FILE = "bucklerhome_secure"
    private const val FALLBACK_FILE = "bucklerhome_secure_fallback"

    @Volatile
    private var cached: SharedPreferences? = null

    fun get(context: Context): SharedPreferences =
        cached ?: synchronized(this) {
            cached ?: create(context.applicationContext).also { cached = it }
        }

    private fun create(context: Context): SharedPreferences {
        runCatching { openEncrypted(context) }.onSuccess { return it }
            .onFailure { Log.w(TAG, "Encrypted prefs unreadable; resetting once", it) }
        context.deleteSharedPreferences(FILE)
        return runCatching { openEncrypted(context) }.getOrElse {
            Log.e(TAG, "Keystore unavailable; tokens stored unencrypted in a fallback file", it)
            context.getSharedPreferences(FALLBACK_FILE, Context.MODE_PRIVATE)
        }
    }

    private fun openEncrypted(context: Context): SharedPreferences =
        EncryptedSharedPreferences.create(
            FILE,
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
}
