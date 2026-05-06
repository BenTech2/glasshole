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
    private val generateThumbBytes: (file: File) -> ByteArray?,
    /** Called when a `POST /upload` lands. The handler chooses where
     *  to land the file (typically `/sdcard/Pictures/GlassHole-Phone/`
     *  or `/sdcard/Movies/GlassHole-Phone/`) and returns the resulting
     *  File on success, or null on failure. The body is already
     *  written to disk before the callback fires.
     *
     *  Receives the request's filename, content type ("image"/"video"),
     *  declared length, and the on-disk staging file. */
    private val acceptUpload: ((name: String, type: String, staged: File) -> File?)? = null
) {
    companion object {
        private const val TAG = "GalleryHttpServer"
        private const val ACCEPT_TIMEOUT_MS = 0  // block until interrupted
        private const val SOCKET_READ_TIMEOUT_MS = 15_000
        // 1 GB ceiling on a single upload — enough for any glass-side
        // recording length we've seen, and a sane backstop against
        // a malicious peer trying to fill /sdcard.
        private const val MAX_UPLOAD_BYTES = 1024L * 1024L * 1024L
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
    fun uploadUrl(ip: String) = "http://$ip:$port/upload?token=$token"

    private fun handleRequest(socket: Socket) {
        socket.use {
            try {
                socket.soTimeout = SOCKET_READ_TIMEOUT_MS
                val input = socket.getInputStream()
                val out = socket.getOutputStream()

                val requestLine = readLine(input) ?: run {
                    writeStatus(out, 400, "Bad Request"); return
                }
                // Read headers — byte-level so a POST body left in the
                // stream isn't pre-buffered by a Reader.
                val headers = HashMap<String, String>()
                while (true) {
                    val h = readLine(input) ?: break
                    if (h.isEmpty()) break
                    val colon = h.indexOf(':')
                    if (colon > 0) {
                        val k = h.substring(0, colon).trim().lowercase()
                        val v = h.substring(colon + 1).trim()
                        headers[k] = v
                    }
                }

                if (!requestLine.contains("token=$token")) {
                    Log.w(TAG, "Bad token: $requestLine")
                    writeStatus(out, 401, "Unauthorized"); return
                }

                val method = requestLine.substringBefore(" ")
                val rawPath = requestLine
                    .substringAfter(" ")
                    .substringBefore(" ")
                val path = rawPath.substringBefore("?")
                val query = rawPath.substringAfter("?", "")

                when (method) {
                    "GET" -> handleGet(out, path)
                    "POST" -> handlePost(out, input, path, query, headers)
                    else -> writeStatus(out, 405, "Method Not Allowed")
                }
            } catch (e: Exception) {
                Log.w(TAG, "request handling failed: ${e.message}")
            }
        }
    }

    private fun handleGet(out: OutputStream, path: String) {
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
    }

    /**
     * `POST /upload?name=<urlencoded>&type=<image|video>` with the file
     * bytes as the body. Content-Length must match — we read exactly
     * that many bytes into a staging file under `cacheDir`, then hand
     * that file to [acceptUpload] which moves/copies it to its final
     * destination on the glass.
     */
    private fun handlePost(
        out: OutputStream,
        input: java.io.InputStream,
        path: String,
        query: String,
        headers: Map<String, String>
    ) {
        val accept = acceptUpload
        if (accept == null || path != "/upload") {
            writeStatus(out, 404, "Not Found"); return
        }
        val params = parseQuery(query)
        val name = params["name"]?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            ?: run { writeStatus(out, 400, "Missing name"); return }
        val type = params["type"] ?: "image"
        val length = headers["content-length"]?.toLongOrNull()
            ?: run { writeStatus(out, 411, "Length Required"); return }
        if (length <= 0L || length > MAX_UPLOAD_BYTES) {
            writeStatus(out, 413, "Payload Too Large"); return
        }

        // Stage to a temp file in the workers' tmp dir. The accept
        // callback is responsible for moving it to a real media path.
        val staging = File.createTempFile("glasshole-upload-", ".bin")
        try {
            staging.outputStream().use { sink ->
                val buf = ByteArray(64 * 1024)
                var remaining = length
                while (remaining > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n <= 0) throw java.io.IOException("client closed mid-upload")
                    sink.write(buf, 0, n)
                    remaining -= n
                }
            }
            val finalFile = accept(name, type, staging)
            if (finalFile == null) {
                writeStatus(out, 500, "Upload rejected"); return
            }
            val ok = "{\"ok\":true,\"name\":\"${jsonEscape(finalFile.name)}\",\"size\":${finalFile.length()}}"
            val bytes = ok.toByteArray()
            out.write((
                "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
            ).toByteArray())
            out.write(bytes)
            out.flush()
        } catch (e: Exception) {
            Log.w(TAG, "upload failed: ${e.message}")
            try { staging.delete() } catch (_: Exception) {}
            writeStatus(out, 500, "Upload Failed")
        }
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        val m = HashMap<String, String>()
        for (pair in query.split("&")) {
            val eq = pair.indexOf('=')
            if (eq > 0) m[pair.substring(0, eq)] = pair.substring(eq + 1)
        }
        return m
    }

    private fun jsonEscape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")

    /**
     * Byte-level readLine that returns the next CRLF-terminated line.
     * Unlike BufferedReader, this leaves the next byte after \n
     * untouched — critical for POST so the request body isn't
     * pre-buffered into a Reader.
     */
    private fun readLine(input: java.io.InputStream): String? {
        val baos = java.io.ByteArrayOutputStream()
        var saw = 0
        while (true) {
            val b = input.read()
            if (b < 0) return if (baos.size() == 0) null else baos.toString("ISO-8859-1")
            if (b == '\n'.code) {
                val raw = baos.toByteArray()
                val len = if (raw.isNotEmpty() && raw.last() == '\r'.code.toByte())
                    raw.size - 1 else raw.size
                return String(raw, 0, len, Charsets.ISO_8859_1)
            }
            baos.write(b)
            saw++
            if (saw > 16_384) return null  // header line too long
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
