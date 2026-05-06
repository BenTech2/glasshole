package com.glasshole.phone.plugins.ssh

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * AES-GCM-encrypted SharedPreferences with a Keystore-wrapped master
 * key. Same pattern as the on-glass [com.glasshole.plugin.ssh.glass.EncryptedPrefs] —
 * documented there.
 */
object EncryptedPrefs {

    private const val TAG = "SshPhoneEncryptedPrefs"

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
