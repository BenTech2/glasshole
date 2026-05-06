package com.glasshole.phone.plugins.ssh

import android.content.Context
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Phone-side store for generated SSH keypairs. Each entry holds a
 * user-friendly name, the OpenSSH-format public key string (suitable
 * for pasting into authorized_keys), and the JSch-format private key
 * PEM (so we can hand it to the glass via SET_PROFILES without any
 * format gymnastics on the other side).
 *
 * Persistence: a single JSON blob in SharedPreferences ("ssh_keys_v1").
 * Same at-rest caveat as SshProfileStore — app sandbox only, no
 * KeyStore wrapping yet. The SSH key manager UI shows a security
 * disclaimer next to the generate button.
 */
class SshKeyStore(context: Context) {

    // AES-GCM-encrypted at rest. Same suffix-bump pattern as the
    // profile store to skip migrating old plaintext data.
    private val prefs = EncryptedPrefs.get(context, "ssh_keys_v2_enc")

    data class StoredKey(
        val id: String = UUID.randomUUID().toString(),
        val name: String,
        val publicKeyOpenSSH: String,
        val privateKeyPem: String,
        val createdAt: Long = System.currentTimeMillis(),
        /** Encrypted with this passphrase if set, else null. */
        val passphraseHint: String? = null
    )

    fun list(): List<StoredKey> {
        val raw = prefs.getString(KEY_JSON, null) ?: return emptyList()
        return try {
            val arr = JSONObject(raw).optJSONArray("keys") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { o ->
                    StoredKey(
                        id = o.optString("id"),
                        name = o.optString("name"),
                        publicKeyOpenSSH = o.optString("publicKey"),
                        privateKeyPem = o.optString("privateKey"),
                        createdAt = o.optLong("createdAt", 0L),
                        passphraseHint = o.optString("passphraseHint").takeIf { it.isNotEmpty() }
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun get(id: String): StoredKey? = list().firstOrNull { it.id == id }

    fun add(key: StoredKey) {
        persist(list() + key)
    }

    fun delete(id: String) {
        persist(list().filter { it.id != id })
    }

    private fun persist(keys: List<StoredKey>) {
        val arr = JSONArray()
        keys.forEach { k ->
            arr.put(JSONObject()
                .put("id", k.id)
                .put("name", k.name)
                .put("publicKey", k.publicKeyOpenSSH)
                .put("privateKey", k.privateKeyPem)
                .put("createdAt", k.createdAt)
                .also { if (k.passphraseHint != null) it.put("passphraseHint", k.passphraseHint) })
        }
        val root = JSONObject().put("version", 1).put("keys", arr).toString()
        prefs.edit().putString(KEY_JSON, root).apply()
    }

    companion object {
        private const val KEY_JSON = "keys_json"

        /**
         * Generate a new Ed25519 keypair via JSch and return both the
         * private-key PEM (for storing + pushing to glass) and the
         * OpenSSH-format public key string (for pasting into the
         * server's authorized_keys).
         *
         * @param comment trailing comment baked into the public key
         *                line — typically the user's email or device
         *                hostname so server admins can audit.
         * @param passphrase optional passphrase that encrypts the
         *                   private key at rest. Glass needs the same
         *                   passphrase at connect time.
         */
        fun generate(comment: String, passphrase: String?): Pair<String, String> {
            val jsch = JSch()
            val kp = KeyPair.genKeyPair(jsch, KeyPair.ED25519)
            try {
                if (!passphrase.isNullOrEmpty()) {
                    kp.setPassphrase(passphrase)
                }
                val privBuf = ByteArrayOutputStream()
                kp.writePrivateKey(privBuf)
                val pubBuf = ByteArrayOutputStream()
                kp.writePublicKey(pubBuf, comment)
                return Pair(
                    String(privBuf.toByteArray(), Charsets.UTF_8),
                    String(pubBuf.toByteArray(), Charsets.UTF_8).trim()
                )
            } finally {
                kp.dispose()
            }
        }
    }
}
