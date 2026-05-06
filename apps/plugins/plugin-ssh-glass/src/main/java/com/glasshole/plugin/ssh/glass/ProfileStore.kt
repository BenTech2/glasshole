package com.glasshole.plugin.ssh.glass

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Glass-side cache of SSH profiles synced from the phone. The phone is
 * the source of truth; this store just holds the most recent
 * `SET_PROFILES` snapshot so the glass picker (and direct OPEN dispatch)
 * can resolve a profile id without round-tripping back to the phone.
 *
 * Storage: a single JSON blob in SharedPreferences. Profiles include
 * password / private-key bytes when the user opted into either; those
 * sit in the standard Android app sandbox at rest. KeyStore-wrapping
 * is a v2 hardening — call it out before we ship if the user wants
 * stronger guarantees.
 */
class ProfileStore(context: Context) {

    // Encrypted at rest: file name carries an `_enc` suffix to stay
    // distinct from any earlier plain-text profile cache that might
    // linger from a pre-encryption build (we don't migrate, the user
    // re-adds — alpha-acceptable).
    private val prefs = EncryptedPrefs.get(context, "ssh_profiles_enc")

    data class Profile(
        val id: String,
        val name: String,
        val host: String,
        val port: Int,
        val user: String,
        val authMode: String,           // "password" | "key"
        val password: String?,
        val privateKeyPem: String?,
        val keyPassphrase: String?
    )

    /** Replace the local snapshot with the phone-supplied JSON.
     *  Caller passes the raw payload from a SET_PROFILES envelope. */
    fun saveSnapshot(payloadJson: String) {
        // Validate parseability before clobbering the existing cache —
        // a malformed push shouldn't wipe good profiles.
        try { JSONObject(payloadJson).optJSONArray("profiles") }
            catch (e: Exception) { return }
        prefs.edit().putString(KEY_JSON, payloadJson).apply()
    }

    fun list(): List<Profile> {
        val raw = prefs.getString(KEY_JSON, null) ?: return emptyList()
        return try {
            val arr = JSONObject(raw).optJSONArray("profiles") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Profile(
                    id = o.optString("id"),
                    name = o.optString("name", o.optString("host")),
                    host = o.optString("host"),
                    port = o.optInt("port", 22),
                    user = o.optString("user"),
                    authMode = o.optString("authMode", "password"),
                    password = o.optString("password").takeIf { it.isNotEmpty() },
                    privateKeyPem = o.optString("privateKeyPem").takeIf { it.isNotEmpty() },
                    keyPassphrase = o.optString("keyPassphrase").takeIf { it.isNotEmpty() }
                ).takeIf { it.host.isNotEmpty() && it.user.isNotEmpty() }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun get(id: String): Profile? = list().firstOrNull { it.id == id }

    fun isEmpty(): Boolean = list().isEmpty()

    companion object {
        private const val KEY_JSON = "profiles_json"
    }
}
