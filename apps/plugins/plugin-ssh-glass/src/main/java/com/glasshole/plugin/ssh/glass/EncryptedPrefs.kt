package com.glasshole.plugin.ssh.glass

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log

/**
 * SSH plugin secret storage. Behavior depends on edition:
 *
 *   API 23+ (EE2): AES-GCM-encrypted [androidx.security.crypto.EncryptedSharedPreferences]
 *     with a Keystore-wrapped master key. The on-disk file is encrypted
 *     and the master key lives in the Android Keystore (hardware-backed
 *     where supported).
 *
 *   API 19–22 (XE / EE1, KitKat): the Keystore APIs we need
 *     (KeyGenParameterSpec / AES key generation) don't exist, so we
 *     fall back to a plain [SharedPreferences]. App-sandbox isolation
 *     is the only barrier — the user-facing SSH page + the on-glass
 *     picker both surface a disclaimer about this. The "store credentials
 *     in plain SharedPreferences" caveat is documented on the build
 *     gradle file too.
 *
 * Recovery: if encrypted prefs become unreadable (keystore key rotated,
 * security-crypto version mismatch), we wipe the file and start fresh
 * rather than crashing on every plugin start.
 */
object EncryptedPrefs {

    private const val TAG = "SshEncryptedPrefs"

    /** True when the platform supports the AES-GCM Keystore wrap that
     *  EncryptedSharedPreferences relies on. KitKat (API 19) lacks the
     *  required [android.security.keystore.KeyGenParameterSpec] APIs. */
    val isEncryptionSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M

    fun get(context: Context, fileName: String): SharedPreferences {
        val ctx = context.applicationContext
        if (!isEncryptionSupported) {
            // KitKat fallback. The disclaimer surfaces this on the
            // phone SSH page + glass picker so the user knows their
            // creds aren't at-rest encrypted on this edition.
            Log.w(TAG, "API ${Build.VERSION.SDK_INT}: storing '$fileName' in plain SharedPreferences")
            return ctx.getSharedPreferences(fileName, Context.MODE_PRIVATE)
        }
        return openEncrypted(ctx, fileName)
    }

    /** Encrypted-path open. Wrapped in its own method so the imports
     *  for androidx.security.crypto only resolve at runtime on API 23+
     *  — they exist in the classpath at compile time but aren't called
     *  on KitKat. */
    private fun openEncrypted(ctx: Context, fileName: String): SharedPreferences {
        val masterKey = androidx.security.crypto.MasterKey.Builder(ctx)
            .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
            .build()
        fun create() = androidx.security.crypto.EncryptedSharedPreferences.create(
            ctx,
            fileName,
            masterKey,
            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        return try {
            create()
        } catch (e: Exception) {
            Log.w(TAG, "Encrypted prefs '$fileName' unreadable, wiping: ${e.message}")
            // Drop the corrupted file and try once more. If it still
            // fails the second call propagates — that's a real bug.
            ctx.deleteSharedPreferences(fileName)
            create()
        }
    }
}
