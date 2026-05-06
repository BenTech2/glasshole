package com.glasshole.phone.plugins.ssh

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Phone-side source of truth for SSH connection profiles. Persists a
 * single JSON blob in SharedPreferences ("ssh_profiles_v1") and exposes
 * snapshot helpers for SET_PROFILES envelope construction.
 *
 * Passwords and pasted private keys live alongside the rest of the
 * profile fields with only the standard Android app-sandbox barrier
 * at rest. KeyStore-wrapping the secrets is a v2 hardening — call it
 * out if/when we ship to non-developer users.
 */
class SshProfileStore(context: Context) {

    // AES-GCM-encrypted at rest. Stays distinct from the
    // pre-encryption "ssh_profiles_v1" file so old plaintext data
    // doesn't pollute the new store; user re-adds (alpha-acceptable).
    private val prefs = EncryptedPrefs.get(context, "ssh_profiles_v2_enc")

    data class Profile(
        val id: String = UUID.randomUUID().toString(),
        val name: String,
        val host: String,
        val port: Int = 22,
        val user: String,
        val authMode: AuthMode,
        val password: String? = null,
        val privateKeyPem: String? = null,
        val keyPassphrase: String? = null
    )

    enum class AuthMode { PASSWORD, KEY }

    fun list(): List<Profile> {
        val raw = prefs.getString(KEY_JSON, null) ?: return emptyList()
        return try {
            val arr = JSONObject(raw).optJSONArray("profiles") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.toProfile() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun get(id: String): Profile? = list().firstOrNull { it.id == id }

    fun upsert(profile: Profile) {
        val current = list().toMutableList()
        val idx = current.indexOfFirst { it.id == profile.id }
        if (idx >= 0) current[idx] = profile else current.add(profile)
        persist(current)
    }

    fun delete(id: String) {
        persist(list().filter { it.id != id })
    }

    /** JSON payload destined for the glass via SET_PROFILES. Mirrors
     *  the schema [com.glasshole.plugin.ssh.glass.ProfileStore] expects. */
    fun snapshotJson(): String = persist(list(), writeBack = false)

    private fun persist(profiles: List<Profile>, writeBack: Boolean = true): String {
        val arr = JSONArray()
        profiles.forEach { p ->
            val o = JSONObject()
                .put("id", p.id)
                .put("name", p.name)
                .put("host", p.host)
                .put("port", p.port)
                .put("user", p.user)
                .put("authMode", if (p.authMode == AuthMode.KEY) "key" else "password")
            if (p.password != null) o.put("password", p.password)
            if (p.privateKeyPem != null) o.put("privateKeyPem", p.privateKeyPem)
            if (p.keyPassphrase != null) o.put("keyPassphrase", p.keyPassphrase)
            arr.put(o)
        }
        val root = JSONObject().put("version", 1).put("profiles", arr).toString()
        if (writeBack) prefs.edit().putString(KEY_JSON, root).apply()
        return root
    }

    private fun JSONObject.toProfile(): Profile? {
        val host = optString("host")
        val user = optString("user")
        if (host.isEmpty() || user.isEmpty()) return null
        val authMode = when (optString("authMode")) {
            "key" -> AuthMode.KEY
            else -> AuthMode.PASSWORD
        }
        return Profile(
            id = optString("id").ifEmpty { UUID.randomUUID().toString() },
            name = optString("name").ifEmpty { "$user@$host" },
            host = host,
            port = optInt("port", 22),
            user = user,
            authMode = authMode,
            password = optString("password").takeIf { it.isNotEmpty() },
            privateKeyPem = optString("privateKeyPem").takeIf { it.isNotEmpty() },
            keyPassphrase = optString("keyPassphrase").takeIf { it.isNotEmpty() }
        )
    }

    companion object {
        private const val KEY_JSON = "profiles_json"
    }
}
