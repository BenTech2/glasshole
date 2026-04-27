package com.glasshole.phone.plugindir.worker

import android.content.Context
import android.util.Log
import com.glasshole.phone.AppLog
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import javax.net.ssl.SSLSocketFactory

/**
 * Reads an anonymous Twitch IRC-over-TLS feed and emits each chat line
 * back to the glass plugin as `emit_type` JSON, plus connection/error
 * breadcrumbs as `status_type` JSON.
 *
 * Expected params:
 * ```
 * {
 *   "start_trigger": "START",
 *   "stop_trigger":  "STOP",
 *   "channel":       "somechannel",
 *   "emit_type":     "CHAT",
 *   "status_type":   "STATUS"
 * }
 * ```
 * The glass plugin arms us with START on open and disarms with STOP on
 * close — we keep no socket open between viewings so there's no quota /
 * battery drain while nothing's watching.
 */
class TwitchChatPrimitive : WorkerPrimitive {

    companion object {
        private const val TAG = "TwitchChatPrim"
        private const val HOST = "irc.chat.twitch.tv"
        private const val PORT = 6697
    }

    private var startTrigger: String = "START"
    private var stopTrigger: String = "STOP"
    private var channel: String = ""
    private var emitType: String = "CHAT"
    private var statusType: String = "STATUS"

    private var emit: ((type: String, payload: String) -> Unit)? = null

    @Volatile private var running = false
    private var thread: Thread? = null
    private var socket: Socket? = null

    override fun start(
        context: Context,
        params: JSONObject,
        emit: (type: String, payload: String) -> Unit
    ) {
        this.startTrigger = params.optString("start_trigger", "START")
        this.stopTrigger = params.optString("stop_trigger", "STOP")
        this.channel = params.optString("channel").trim().removePrefix("#")
        this.emitType = params.optString("emit_type", "CHAT")
        this.statusType = params.optString("status_type", "STATUS")
        this.emit = emit
        Log.i(TAG, "armed: channel=$channel")
    }

    override fun onMessage(type: String, payload: String) {
        when (type) {
            startTrigger -> beginConnect()
            stopTrigger -> disconnect()
        }
    }

    override fun onConnectionChanged(connected: Boolean) {
        if (!connected) disconnect()
    }

    override fun stop() {
        disconnect()
        emit = null
    }

    private fun beginConnect() {
        if (channel.isEmpty()) {
            emitStatus("Twitch channel not set — open plugin settings on phone")
            AppLog.log(TAG, "channel not configured")
            return
        }
        if (running) return
        running = true
        thread = Thread({ run() }, "TwitchChatPrimitive").also { it.start() }
    }

    private fun disconnect() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        try { thread?.interrupt() } catch (_: Exception) {}
        thread = null
    }

    private fun run() {
        // Reconnect loop with mild backoff so a transient drop doesn't kill
        // the session. The nick must be unique-ish per connection.
        var backoff = 2_000L
        while (running) {
            try {
                emitStatus("Connecting to Twitch #$channel…")
                val sslSock = SSLSocketFactory.getDefault().createSocket(HOST, PORT) as Socket
                socket = sslSock
                val reader = BufferedReader(InputStreamReader(sslSock.getInputStream(), Charsets.UTF_8))
                val writer = PrintWriter(sslSock.getOutputStream().bufferedWriter(Charsets.UTF_8), false)

                val nick = "justinfan" + (10000 + (Math.random() * 89999).toInt())
                writer.print("CAP REQ :twitch.tv/tags\r\n")
                writer.print("PASS SCHMOOPIIE\r\n")
                writer.print("NICK $nick\r\n")
                writer.print("JOIN #${channel.lowercase()}\r\n")
                writer.flush()

                emitStatus("Joined #$channel — waiting for messages")
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
                    emitStatus("Reconnecting to #$channel…")
                }
            } finally {
                try { socket?.close() } catch (_: Exception) {}
                socket = null
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
     * We only care about PRIVMSG; everything else is server chatter.
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
        emitChat(user, text, color)
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

    private fun emitChat(user: String, text: String, color: String) {
        val json = JSONObject().apply {
            put("user", user)
            put("text", text)
            if (color.isNotEmpty()) put("color", color)
        }.toString()
        emit?.invoke(emitType, json)
    }

    private fun emitStatus(text: String) {
        val json = JSONObject().apply { put("text", text) }.toString()
        emit?.invoke(statusType, json)
    }
}
