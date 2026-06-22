package com.glasshole.glassee1

import android.content.Context
import android.net.wifi.WifiManager
import android.text.format.Formatter
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import kotlin.math.min

/**
 * One-shot HTTP upload server for phone → glass wallpaper transfer.
 *
 * Listens on a random LAN port for a single `POST /upload?token=…&
 * filename=…` request, copies the body to [onComplete], and stops
 * itself. Same shape as MjpegHttpServer in the SDK but flipped:
 * phone is the client, glass is receiving bytes.
 *
 * The token is generated per-instance so a stale phone holding an
 * old URL can't sneak in past a teardown / restart. Bodies above
 * [MAX_SIZE_BYTES] are refused with 413 so a misbehaving phone can't
 * exhaust glass storage.
 */
class WallpaperUploadServer(
    private val context: Context,
    private val onComplete: (filename: String, bytes: ByteArray) -> Unit,
    private val onError: (reason: String) -> Unit,
    /** Per-instance size cap so the APK-install flow can accept much
     *  bigger payloads without bumping the wallpaper / notif-sound
     *  defaults. Pass null to use [DEFAULT_MAX_SIZE_BYTES]. */
    private val maxSizeBytesOverride: Long? = null,
) {

    companion object {
        private const val TAG = "WallpaperUpload"
        /** 5 MB default — wallpapers + notif-sound clips. APK installs
         *  override to a larger cap. */
        const val DEFAULT_MAX_SIZE_BYTES = 5L * 1024 * 1024
        /** Back-compat alias for callers outside the APK-install path. */
        const val MAX_SIZE_BYTES = DEFAULT_MAX_SIZE_BYTES
        /** APK uploads — same cap EE2 uses. */
        const val APK_INSTALL_MAX_SIZE_BYTES = 100L * 1024 * 1024
        /** How long the server stays up waiting for the phone. After
         *  this it tears itself down so we're not leaking a listening
         *  socket if the phone never POSTs. */
        const val IDLE_TIMEOUT_MS = 60_000L
    }

    private val token: String = UUID.randomUUID().toString().replace("-", "").take(16)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var timeoutThread: Thread? = null
    @Volatile private var consumed: Boolean = false

    /**
     * Open the listening socket and return the URL the phone should
     * POST to. Returns null if the device isn't on Wi-Fi (no LAN IP
     * to advertise). Idempotent: re-calling returns the same URL
     * while the server is alive.
     */
    @Synchronized
    fun start(): String? {
        if (serverSocket != null) {
            val ss = serverSocket ?: return null
            val ip = wifiIp() ?: return null
            return "http://$ip:${ss.localPort}/upload?token=$token"
        }
        val ip = wifiIp() ?: return null
        val ss = try {
            ServerSocket(0)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open server socket: ${e.message}")
            return null
        }
        serverSocket = ss

        acceptThread = Thread {
            while (!ss.isClosed) {
                val client = try {
                    ss.accept()
                } catch (_: Exception) { break }
                Thread { handle(client) }.apply {
                    isDaemon = true
                    name = "WallpaperUpload-worker"
                    start()
                }
            }
        }.apply { isDaemon = true; name = "WallpaperUpload-accept"; start() }

        timeoutThread = Thread {
            try { Thread.sleep(IDLE_TIMEOUT_MS) } catch (_: InterruptedException) {}
            if (!consumed) {
                Log.w(TAG, "Idle timeout — tearing down server")
                onError("timeout")
                stop()
            }
        }.apply { isDaemon = true; name = "WallpaperUpload-timeout"; start() }

        val url = "http://$ip:${ss.localPort}/upload?token=$token"
        Log.i(TAG, "Listening on $url")
        return url
    }

    @Synchronized
    fun stop() {
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        acceptThread = null
        timeoutThread?.interrupt()
        timeoutThread = null
    }

    private fun handle(socket: Socket) {
        socket.use {
            try {
                val input = socket.getInputStream()
                val out = socket.getOutputStream()

                // Read until end-of-headers (\r\n\r\n). Manual byte-
                // level parse so we don't accidentally buffer body
                // bytes inside a BufferedReader.
                val headerBuf = ByteArrayOutputStream(512)
                while (true) {
                    val b = input.read()
                    if (b < 0) {
                        writeStatus(out, 400, "Truncated headers"); return
                    }
                    headerBuf.write(b)
                    val arr = headerBuf.toByteArray()
                    if (arr.size >= 4 &&
                        arr[arr.size - 4] == '\r'.code.toByte() &&
                        arr[arr.size - 3] == '\n'.code.toByte() &&
                        arr[arr.size - 2] == '\r'.code.toByte() &&
                        arr[arr.size - 1] == '\n'.code.toByte()
                    ) break
                    if (headerBuf.size() > 8 * 1024) {
                        writeStatus(out, 431, "Headers too large"); return
                    }
                }
                val headerText = headerBuf.toString(Charsets.UTF_8.name())
                val lines = headerText.split("\r\n")
                val requestLine = lines.firstOrNull().orEmpty()
                if (!requestLine.startsWith("POST ")) {
                    writeStatus(out, 405, "Method Not Allowed"); return
                }
                if (!requestLine.contains("token=$token")) {
                    Log.w(TAG, "Token mismatch on $requestLine")
                    writeStatus(out, 401, "Unauthorized"); return
                }
                val filename = extractQueryParam(requestLine, "filename") ?: "wallpaper.jpg"

                val headers = mutableMapOf<String, String>()
                for (line in lines.drop(1)) {
                    if (line.isEmpty()) continue
                    val sep = line.indexOf(':')
                    if (sep > 0) {
                        headers[line.substring(0, sep).trim().lowercase()] =
                            line.substring(sep + 1).trim()
                    }
                }
                val contentLength = headers["content-length"]?.toLongOrNull() ?: -1L
                if (contentLength <= 0L) {
                    writeStatus(out, 411, "Length Required"); return
                }
                val maxSize = maxSizeBytesOverride ?: DEFAULT_MAX_SIZE_BYTES
                if (contentLength > maxSize) {
                    writeStatus(out, 413, "Payload Too Large"); return
                }

                // Drain Content-Length bytes off the wire into memory.
                // Wallpapers are small (<MAX_SIZE_BYTES) so a heap
                // buffer is fine; we don't want to hold a partial file
                // on disk if the upload's interrupted.
                val body = ByteArrayOutputStream(contentLength.toInt())
                val buf = ByteArray(8 * 1024)
                var remaining = contentLength
                while (remaining > 0) {
                    val toRead = min(buf.size.toLong(), remaining).toInt()
                    val n = input.read(buf, 0, toRead)
                    if (n < 0) {
                        writeStatus(out, 400, "Truncated body"); return
                    }
                    body.write(buf, 0, n)
                    remaining -= n
                }

                writeStatus(out, 200, "OK")
                out.flush()
                consumed = true
                Log.i(TAG, "Upload complete: $filename ${body.size()} bytes")
                onComplete(filename, body.toByteArray())
                // Single-shot: tear down so the next upload starts
                // fresh (new port, new token).
                stop()
            } catch (e: Exception) {
                Log.w(TAG, "request failed: ${e.message}")
                onError("io:${e.message}")
            }
        }
    }

    private fun writeStatus(out: OutputStream, code: Int, msg: String) {
        try {
            out.write((
                "HTTP/1.1 $code $msg\r\n" +
                    "Content-Length: 0\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray())
            out.flush()
        } catch (_: IOException) {}
    }

    private fun extractQueryParam(requestLine: String, name: String): String? {
        val path = requestLine.substringAfter(' ').substringBefore(' ')
        val q = path.substringAfter('?', "")
        if (q.isEmpty()) return null
        for (pair in q.split('&')) {
            val eq = pair.indexOf('=')
            if (eq < 0) continue
            if (pair.substring(0, eq) == name) {
                return java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
            }
        }
        return null
    }

    private fun wifiIp(): String? {
        return try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ip = wifi.connectionInfo?.ipAddress ?: 0
            if (ip == 0) null else Formatter.formatIpAddress(ip)
        } catch (_: Exception) { null }
    }
}
