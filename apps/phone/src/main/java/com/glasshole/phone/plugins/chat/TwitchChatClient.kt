package com.glasshole.phone.plugins.chat

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import javax.net.ssl.SSLSocketFactory

/**
 * Anonymous Twitch chat reader via raw IRC-over-TLS. No auth is needed
 * because read-only access is public — NICK `justinfan<digits>` is the
 * documented anonymous convention. We request the "tags" capability so
 * PRIVMSG lines come back with color + display-name fields, which lets
 * the glass UI render user names in their Twitch colour.
 */
class TwitchChatClient(
    private val channel: String,
    private val onMessage: (user: String, text: String, color: String) -> Unit,
    private val onStatus: (String) -> Unit
) {

    companion object {
        private const val TAG = "TwitchChatClient"
        private const val HOST = "irc.chat.twitch.tv"
        private const val PORT = 6697
    }

    @Volatile private var running = false
    private var thread: Thread? = null
    private var socket: Socket? = null
    private var out: PrintWriter? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ run() }, "TwitchChatClient").also { it.start() }
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        out = null
        try { thread?.interrupt() } catch (_: Exception) {}
        thread = null
    }

    private fun run() {
        // Reconnect loop with mild backoff so a transient drop doesn't kill
        // the session. The nick must be unique-ish per connection.
        var backoff = 2_000L
        while (running) {
            try {
                onStatus("Connecting to Twitch #$channel…")
                val sslSock = SSLSocketFactory.getDefault().createSocket(HOST, PORT) as Socket
                socket = sslSock
                val reader = BufferedReader(InputStreamReader(sslSock.getInputStream(), Charsets.UTF_8))
                val writer = PrintWriter(sslSock.getOutputStream().bufferedWriter(Charsets.UTF_8), false)
                out = writer

                val nick = "justinfan" + (10000 + (Math.random() * 89999).toInt())
                writer.print("CAP REQ :twitch.tv/tags\r\n")
                writer.print("PASS SCHMOOPIIE\r\n")
                writer.print("NICK $nick\r\n")
                writer.print("JOIN #${channel.lowercase()}\r\n")
                writer.flush()

                onStatus("Joined #$channel — waiting for messages")
                backoff = 2_000L

                while (running) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) continue
                    if (line.startsWith("PING")) {
                        writer.print("PONG ${line.substring(4)}\r\n")
                        writer.flush()
                        continue
                    }
                    handleLine(line)
                }
            } catch (e: Exception) {
                if (running) {
                    Log.w(TAG, "Twitch connection error: ${e.message}")
                    onStatus("Reconnecting to #$channel…")
                }
            } finally {
                try { socket?.close() } catch (_: Exception) {}
                socket = null
                out = null
            }

            if (running) {
                try { Thread.sleep(backoff) } catch (_: InterruptedException) { return }
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }

    /**
     * IRC v3 line with optional tags:
     *   [@tags] :<user>!... PRIVMSG #channel :<text>
     * We only care about PRIVMSG; everything else is chatter from the server
     * (NICK accepted, MODE, JOIN/PART, etc) — safe to ignore.
     */
    private fun handleLine(line: String) {
        var remainder = line
        var tags: Map<String, String> = emptyMap()
        if (remainder.startsWith("@")) {
            val sp = remainder.indexOf(' ')
            if (sp < 0) return
            tags = parseTags(remainder.substring(1, sp))
            remainder = remainder.substring(sp + 1)
        }
        if (!remainder.startsWith(":")) return
        val afterPrefix = remainder.substring(1)
        val spaceIdx = afterPrefix.indexOf(' ')
        if (spaceIdx < 0) return
        val prefix = afterPrefix.substring(0, spaceIdx)
        val rest = afterPrefix.substring(spaceIdx + 1)

        if (!rest.startsWith("PRIVMSG ")) return
        val afterCmd = rest.substring("PRIVMSG ".length)
        val msgIdx = afterCmd.indexOf(" :")
        if (msgIdx < 0) return
        val text = afterCmd.substring(msgIdx + 2)

        val user = tags["display-name"].orEmpty().ifEmpty {
            prefix.substringBefore('!').ifEmpty { "anon" }
        }
        val color = tags["color"].orEmpty()
        onMessage(user, text, color)
    }

    private fun parseTags(raw: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (kv in raw.split(';')) {
            val eq = kv.indexOf('=')
            if (eq <= 0) continue
            out[kv.substring(0, eq)] = kv.substring(eq + 1)
        }
        return out
    }
}
