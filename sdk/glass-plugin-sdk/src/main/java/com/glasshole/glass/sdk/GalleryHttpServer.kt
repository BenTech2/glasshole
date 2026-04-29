package com.glasshole.glass.sdk

import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.UUID

/**
 * Persistent multi-resource HTTP file server for the gallery plugin.
 * Serves two route shapes, both authenticated by `?token=<token>`:
 *
 *   GET /file/<id>?token=…    — full file stream
 *   GET /thumb/<id>?token=…   — JPEG thumbnail (~120px square)
 *
 * One instance lives alongside the plugin service for as long as the
 * service is alive — accepts in a loop on a daemon thread, dispatches
 * each connection to its own short-lived worker so concurrent
 * thumbnail loads from the phone don't head-of-line block.
 *
 * Token: 16 random hex chars per server instance. Phone gets it from
 * the LIST response. Anyone else on the LAN can hit the listening
 * port but can't pull files without the token.
 */
class GalleryHttpServer(
    private val resolveFile: (id: String) -> File?,
    private val generateThumbBytes: (file: File) -> ByteArray?
) {
    companion object {
        private const val TAG = "GalleryHttpServer"
        private const val ACCEPT_TIMEOUT_MS = 0  // block until interrupted
        private const val SOCKET_READ_TIMEOUT_MS = 15_000
    }

    val token: String = UUID.randomUUID().toString().replace("-", "").take(16)

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    /** Listening port. Zero until [start] succeeds. */
    val port: Int get() = serverSocket?.localPort ?: 0

    @Synchronized
    fun start() {
        if (serverSocket != null) return
        val ss = ServerSocket(0).apply { soTimeout = ACCEPT_TIMEOUT_MS }
        serverSocket = ss
        acceptThread = Thread {
            while (!ss.isClosed) {
                val client = try {
                    ss.accept()
                } catch (_: SocketException) {
                    break  // closed
                } catch (e: Exception) {
                    Log.w(TAG, "accept failed: ${e.message}")
                    continue
                }
                Thread { handleRequest(client) }.apply {
                    isDaemon = true
                    name = "GalleryHttpServer-worker"
                    start()
                }
            }
        }.apply { isDaemon = true; name = "GalleryHttpServer-accept"; start() }
        Log.i(TAG, "Started on port ${ss.localPort}")
    }

    @Synchronized
    fun stop() {
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        acceptThread = null
    }

    fun fileUrl(ip: String, id: String) = "http://$ip:$port/file/$id?token=$token"
    fun thumbUrl(ip: String, id: String) = "http://$ip:$port/thumb/$id?token=$token"

    private fun handleRequest(socket: Socket) {
        socket.use {
            try {
                socket.soTimeout = SOCKET_READ_TIMEOUT_MS
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val out = socket.getOutputStream()

                val requestLine = reader.readLine() ?: run {
                    writeStatus(out, 400, "Bad Request"); return
                }
                // Drain headers.
                while (true) {
                    val h = reader.readLine() ?: break
                    if (h.isEmpty()) break
                }
                if (!requestLine.startsWith("GET ")) {
                    writeStatus(out, 405, "Method Not Allowed"); return
                }
                if (!requestLine.contains("token=$token")) {
                    Log.w(TAG, "Bad token: $requestLine")
                    writeStatus(out, 401, "Unauthorized"); return
                }

                val path = requestLine
                    .substringAfter(" ")
                    .substringBefore(" ")
                    .substringBefore("?")
                val parts = path.removePrefix("/").split("/")
                if (parts.size < 2) {
                    writeStatus(out, 404, "Not Found"); return
                }
                val (kind, id) = parts[0] to parts[1]
                val file = resolveFile(id) ?: run {
                    writeStatus(out, 404, "Not Found"); return
                }

                when (kind) {
                    "file" -> serveFile(out, file)
                    "thumb" -> serveThumb(out, file)
                    else -> writeStatus(out, 404, "Not Found")
                }
            } catch (e: Exception) {
                Log.w(TAG, "request handling failed: ${e.message}")
            }
        }
    }

    private fun serveFile(out: OutputStream, file: File) {
        if (!file.exists()) { writeStatus(out, 404, "Not Found"); return }
        val length = file.length()
        out.write(("HTTP/1.1 200 OK\r\n" +
            "Content-Length: $length\r\n" +
            "Content-Type: application/octet-stream\r\n" +
            "Connection: close\r\n\r\n").toByteArray())
        try {
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                }
            }
            out.flush()
        } catch (_: IOException) {
            // Client disconnect mid-stream is fine.
        }
    }

    private fun serveThumb(out: OutputStream, file: File) {
        val bytes = generateThumbBytes(file)
        if (bytes == null) { writeStatus(out, 500, "Thumb Failed"); return }
        out.write(("HTTP/1.1 200 OK\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Content-Type: image/jpeg\r\n" +
            "Cache-Control: max-age=3600\r\n" +
            "Connection: close\r\n\r\n").toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun writeStatus(out: OutputStream, code: Int, msg: String) {
        try {
            out.write(("HTTP/1.1 $code $msg\r\n" +
                "Content-Length: 0\r\n" +
                "Connection: close\r\n\r\n").toByteArray())
            out.flush()
        } catch (_: Exception) {}
    }
}
