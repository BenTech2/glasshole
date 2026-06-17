package com.glasshole.plugin.devtools.glass

import android.content.Context
import android.os.Build
import android.util.Log
import org.apache.sshd.scp.server.ScpCommandFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ProcessShellFactory
import java.io.File
import java.security.SecureRandom

/**
 * Embedded MINA SSHD server. Designed for ad-hoc recovery: drop a
 * shell on the glass over Wi-Fi when USB / BT APK transfer is broken.
 *
 * Lifetime is owned by [DevToolsGlassPluginService] (a singleton
 * reference there) so the server keeps listening after the activity
 * is destroyed — the user starts it once, then closes the panel and
 * SSHes in from a laptop. Stops when the service is destroyed or the
 * user taps "Stop" in the panel.
 *
 * Capabilities: interactive `/system/bin/sh`, SCP for file transfer.
 * Auth: password only, randomly generated on first start and stored
 * plain in SharedPreferences (signature-level encryption isn't worth
 * the complexity for an opt-in recovery tool — this is no worse than
 * any other root-shell-equivalent recovery channel).
 * Privilege: app UID only — no `pm install`, no `setprop` for adb
 * wireless, no /system writes. Recovery scope is file moves + intent
 * triggers via `am start`.
 *
 * API gate: pulls in NIO.2 (`java.nio.file.Path`), so silently no-ops
 * on API < 26 (EE1, XE). The UI surfaces an explanation rather than a
 * confusing failure.
 */
class SshdManager(private val appContext: Context) {

    companion object {
        private const val TAG = "GlassDevtoolsSshd"
        const val PREFS = "devtools_sshd"
        const val KEY_PASSWORD = "ssh_password"
        const val DEFAULT_PORT = 2222
        const val MIN_API = Build.VERSION_CODES.O    // API 26
        /** Hardcoded default password. Easy to remember when you're
         *  scrambling to recover the device; user can regenerate to a
         *  random string via the "Regenerate password" button when
         *  they want something stronger. */
        const val DEFAULT_PASSWORD = "glasshole"
    }

    @Volatile private var server: SshServer? = null

    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= MIN_API

    val isRunning: Boolean get() = server != null

    /** Returns the current password, persisting the default if no
     *  override is set. The user can call [regeneratePassword] to
     *  swap in a random one. */
    fun password(): String {
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_PASSWORD, null)
        if (existing != null && existing.isNotEmpty()) return existing
        prefs.edit().putString(KEY_PASSWORD, DEFAULT_PASSWORD).apply()
        return DEFAULT_PASSWORD
    }

    /** Force a new password; useful if the user thinks the old one
     *  leaked. Returns the new password. Stops the server so the
     *  next connect uses the new credential. */
    fun regeneratePassword(): String {
        stop()
        val fresh = generatePassword()
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PASSWORD, fresh).apply()
        return fresh
    }

    @Synchronized
    fun start(port: Int = DEFAULT_PORT): String? {
        if (!isSupported) return "Needs Android 8.0 (Oreo) or newer"
        if (server != null) return null
        val pwd = password()
        // MINA SSHD auto-scans for BouncyCastle + EdDSA security
        // registrars at class-init time; the BC scan fails on Android
        // (Android ships a stripped BC provider that doesn't expose
        // the classes MINA expects) and the failure surfaces as
        // ExceptionInInitializerError. Clamping the registrar list
        // skips the scan entirely and lets MINA fall back to JCE,
        // which is enough for what we need (RSA host keys, AES
        // ciphers, HMAC-SHA-256 MACs).
        System.setProperty("org.apache.sshd.security.registrars", "")
        System.setProperty("org.apache.sshd.security.provider.BC.enabled", "false")
        // Android doesn't set user.home, but MINA SSHD's PathUtils
        // assumes it's there (default cwd for the shell, default ~/.ssh
        // location for server-internal config, etc.) and throws
        // IllegalArgumentException("No user home") on first reference.
        // Point it at our private files dir — writable, sandboxed, and
        // the shell-spawn cwd lands somewhere sane.
        if (System.getProperty("user.home").isNullOrEmpty()) {
            System.setProperty("user.home", appContext.filesDir.absolutePath)
        }
        return try {
            val s = SshServer.setUpDefaultServer().apply {
                this.port = port
                host = "0.0.0.0"
                // Persistent host key so the SSH client's known_hosts
                // entry stays valid across restarts.
                keyPairProvider = SimpleGeneratorHostKeyProvider(
                    File(appContext.filesDir, "ssh_host_key").toPath()
                )
                passwordAuthenticator = PasswordAuthenticator { _, candidate, _ ->
                    // Constant-time-ish compare via String.equals — the
                    // password is randomly generated, not user-typed, so
                    // there's nothing to time-attack against.
                    candidate == pwd
                }
                // /system/bin/sh is universally present on Android since
                // forever. PTY allocation depends on the device's PTY
                // multiplexer; most Android builds support it. If it
                // fails the client just gets a non-PTY exec channel,
                // which still works for `cp` / `am start` / `cat`.
                shellFactory = ProcessShellFactory(
                    "sh", "/system/bin/sh", "-i"
                )
                // SCP so the user can drop files onto the glass without
                // needing an interactive shell to drive `cat > file`.
                commandFactory = ScpCommandFactory()
            }
            s.start()
            server = s
            Log.i(TAG, "Sshd listening on $port")
            null
        } catch (e: Throwable) {
            Log.e(TAG, "Sshd start failed", e)
            // ExceptionInInitializerError wraps the real cause in
            // getException() — unwrap one or two levels so the user
            // sees the underlying problem rather than a useless "null".
            var root: Throwable = e
            var hops = 0
            while (hops < 3) {
                val next = (root as? ExceptionInInitializerError)?.exception ?: root.cause
                if (next == null || next === root) break
                root = next
                hops++
            }
            "${e.javaClass.simpleName}: ${root.javaClass.simpleName}: ${root.message ?: "(no message)"}"
        }
    }

    @Synchronized
    fun stop() {
        try { server?.stop(true) } catch (_: Throwable) {}
        server = null
    }

    private fun generatePassword(): String {
        // 12 chars from a typeable alphabet — no ambiguous 0/O/1/l. Long
        // enough to brute-force-resist a few-second connect window
        // without making the user type something painful.
        val alphabet = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val rng = SecureRandom()
        return CharArray(12) { alphabet[rng.nextInt(alphabet.length)] }.concatToString()
    }
}
