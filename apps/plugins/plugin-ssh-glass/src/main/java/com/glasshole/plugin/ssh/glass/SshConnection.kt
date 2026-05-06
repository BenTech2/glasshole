package com.glasshole.plugin.ssh.glass

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.util.Properties
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Thin JSch wrapper that owns one SSH session + one shell channel.
 * Connection runs on a background thread; reads from the SSH stdout
 * pump thread are forwarded via [onBytes]; exit / errors flow through
 * [onStatus] (visible to the user) and [onClosed] (terminal lifecycle).
 *
 * StrictHostKeyChecking is intentionally disabled for the phase-3
 * proof-of-life — host-key TOFU + persisted known_hosts lands with
 * the profile-store work in phase 4.
 */
class SshConnection(
    private val host: String,
    private val port: Int,
    private val user: String,
    /** Either a password (when [privateKeyPem] is null) or null when
     *  using key auth. */
    private val password: String?,
    /** OpenSSH-format private key (PEM-encoded) for key auth, or null. */
    private val privateKeyPem: String?,
    /** Passphrase for [privateKeyPem]. May be null if the key is
     *  unencrypted. Ignored when [privateKeyPem] is null. */
    private val keyPassphrase: String?,
    private val cols: Int,
    private val rows: Int,
    private val cellWidth: Int,
    private val cellHeight: Int,
    private val onBytes: (ByteArray, Int) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onClosed: (Int) -> Unit
) {

    @Volatile private var jschSession: Session? = null
    @Volatile private var channel: ChannelShell? = null
    private var pumpThread: Thread? = null
    /** Single-threaded executor that owns all writes to the SSH stdin
     *  OutputStream. JSch's stream does blocking network I/O so we
     *  can't touch it from the UI thread (NetworkOnMainThreadException
     *  on Android). Serializing through one thread also keeps writes
     *  in user-typed order under load. */
    private val writerExec: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SSH-writer").apply { isDaemon = true }
    }
    @Volatile private var closed = false

    fun connect() {
        Thread({ runConnect() }, "SSH-connect").also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun runConnect() {
        try {
            onStatus("Connecting to $user@$host:$port…")
            val jsch = JSch()
            // Key auth wins when both are supplied — JSch can also fall
            // back to password if the server rejects the key, but that
            // muddies the failure message. Stick to whatever the
            // profile asked for.
            if (privateKeyPem != null) {
                val passphraseBytes = keyPassphrase?.toByteArray() ?: ByteArray(0)
                jsch.addIdentity(
                    /* name = */ "$user@$host",
                    /* prvkey = */ privateKeyPem.toByteArray(),
                    /* pubkey = */ null,
                    /* passphrase = */ passphraseBytes
                )
            }
            val s = jsch.getSession(user, host, port).apply {
                if (privateKeyPem == null && password != null) setPassword(password)
                val cfg = Properties().apply {
                    setProperty("StrictHostKeyChecking", "no")
                    setProperty(
                        "PreferredAuthentications",
                        if (privateKeyPem != null) "publickey"
                        else "password,keyboard-interactive"
                    )
                }
                setConfig(cfg)
            }
            s.connect(15_000)
            jschSession = s

            val ch = s.openChannel("shell") as ChannelShell
            ch.setPtyType("xterm-256color")
            ch.setPtySize(cols, rows, cols * cellWidth, rows * cellHeight)
            val sshIn = ch.inputStream
            ch.connect(15_000)
            channel = ch
            onStatus("Connected.")

            pumpThread = Thread({
                val buf = ByteArray(4096)
                try {
                    while (!closed) {
                        val n = sshIn.read(buf)
                        if (n <= 0) break
                        onBytes(buf, n)
                    }
                } catch (_: Exception) {
                    // pump shutdown — fall through to onClosed
                }
                onClosed(runCatching { ch.exitStatus }.getOrDefault(-1))
            }, "SSH-pump").also {
                it.isDaemon = true
                it.start()
            }
        } catch (e: Exception) {
            onStatus("SSH failed: ${e.message}")
            onClosed(-1)
        }
    }

    /** Forward keystrokes / mouse events from emulator → remote. Safe
     *  to call from the UI thread; JSch's OutputStream is buffered. */
    fun write(data: ByteArray, offset: Int, count: Int) {
        if (closed) return
        // Copy out of the caller's buffer — JSch's pipe is shared
        // between callers and writes happen later on the writer thread,
        // so we can't trust [data] to stay valid past this call.
        val slice = ByteArray(count)
        System.arraycopy(data, offset, slice, 0, count)
        writerExec.execute {
            try {
                val out = channel?.outputStream ?: return@execute
                out.write(slice, 0, count)
                out.flush()
            } catch (_: Exception) {
                // Channel died mid-write; pump thread will deliver onClosed.
            }
        }
    }

    /** Push a new pty window size to the remote. Trigger this when
     *  the emulator's columns/rows change (e.g. font-size tweak). */
    fun resize(cols: Int, rows: Int, cellW: Int, cellH: Int) {
        try { channel?.setPtySize(cols, rows, cols * cellW, rows * cellH) } catch (_: Exception) {}
    }

    fun disconnect() {
        closed = true
        try { channel?.disconnect() } catch (_: Exception) {}
        try { jschSession?.disconnect() } catch (_: Exception) {}
        writerExec.shutdownNow()
    }
}
