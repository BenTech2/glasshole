package com.glasshole.plugin.ssh.glass

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * AES-GCM-encrypted SharedPreferences with a Keystore-wrapped master
 * key. The on-disk file is encrypted; the master key lives in the
 * Android Keystore (hardware-backed where the device supports it,
 * software-backed otherwise).
 *
 * Recovery: if the keystore master key was rotated or the file is
 * otherwise unreadable (e.g. due to an EncryptedSharedPreferences
 * version bump that broke binary compat), we wipe the file and start
 * fresh. The user re-adds whatever they had — same as if the data
 * never existed. Better than crashing on every plugin start.
 */
object EncryptedPrefs {

    private const val TAG = "SshEncryptedPrefs"

    fun get(context: Context, fileName: String): SharedPreferences {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return try {
            EncryptedSharedPreferences.create(
                context.applicationContext,
                fileName,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.w(TAG, "Encrypted prefs '$fileName' unreadable, wiping: ${e.message}")
            // Drop the corrupted file and try once more. If it still
            // fails the second call propagates — that's a real bug.
            context.applicationContext.deleteSharedPreferences(fileName)
            EncryptedSharedPreferences.create(
                context.applicationContext,
                fileName,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }
}
