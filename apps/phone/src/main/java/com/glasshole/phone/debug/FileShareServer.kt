package com.glasshole.phone.debug

import android.content.Context
import android.net.wifi.WifiManager
import android.text.format.Formatter
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Minimal LAN HTTP file server for the Debug screen. Serves a directory
 * tree on the phone (defaults to the app's external-files
 * `http-share/` so we don't need any storage permissions). Browsable
 * from the glass's built-in browser, which is the recovery path when
 * USB is broken and the APK Manager's BT transfer is unreliable.
 *
 * Cleartext HTTP only — network_security_config already permits it for
 * the wallpaper-upload flow, so no extra config needed. The server is
 * bound to the Wi-Fi interface IP; nothing leaks past the local subnet.
 *
 * No directory upload, no auth, no range requests. Adding any of those
 * is a future-rewrite job — this server's only customer is "open a
 * URL on the glass, tap an APK, install."
 */
class FileShareServer(
    private val context: Context,
    /** Root directory to serve. Must already exist before start(). */
    private val root: File,
) {
    @Volatile private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    /** Current advertised URL, or null when the server isn't running. */
    @Volatile var url: String? = null
        private set

    @Synchronized
    fun start(): String? {
        if (serverSocket != null) return url
        val ip = wifiIp() ?: run {
            Log.w(TAG, "No Wi-Fi IP — refusing to start file server")
            return null
        }
        if (!root.exists()) root.mkdirs()
        val ss = try { ServerSocket(0) } catch (e: Exception) {
            Log.w(TAG, "ServerSocket open failed: ${e.message}")
            return null
        }
        serverSocket = ss
        val advertisedUrl = "http://$ip:${ss.localPort}/"
        url = advertisedUrl

        acceptThread = Thread {
            while (!ss.isClosed) {
                val client = try { ss.accept() } catch (_: Exception) { break }
                Thread { handle(client) }.apply {
                    isDaemon = true
                    name = "FileShare-worker"
                    start()
                }
            }
        }.apply { isDaemon = true; name = "FileShare-accept"; start() }

        Log.i(TAG, "Listening on $advertisedUrl, root=${root.absolutePath}")
        return advertisedUrl
    }

    @Synchronized
    fun stop() {
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        acceptThread = null
        url = null
    }

    private fun handle(socket: Socket) {
        socket.use { sock ->
            try {
                val input = sock.getInputStream().bufferedReader()
                val out = sock.getOutputStream()
                val requestLine = input.readLine().orEmpty()
                // Drain headers — we don't need them but the client
                // expects us to read past the blank line.
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                }
                if (!requestLine.startsWith("GET ")) {
                    writeStatus(out, 405, "Method Not Allowed")
                    return
                }
                val rawPath = requestLine.substringAfter(' ').substringBefore(' ')
                    .substringBefore('?')
                val decoded = try {
                    URLDecoder.decode(rawPath, "UTF-8")
                } catch (_: Exception) {
                    writeStatus(out, 400, "Bad Request"); return
                }
                // Defence against `../` — resolve and confirm the
                // resulting absolute path is still under root.
                val resolved = File(root, decoded.trimStart('/')).canonicalFile
                val rootCanon = root.canonicalFile
                if (!resolved.path.startsWith(rootCanon.path)) {
                    writeStatus(out, 403, "Forbidden"); return
                }
                if (!resolved.exists()) {
                    writeStatus(out, 404, "Not Found"); return
                }
                if (resolved.isDirectory) {
                    serveDirectory(out, resolved, decoded)
                } else {
                    serveFile(out, resolved)
                }
            } catch (e: Exception) {
                Log.w(TAG, "handle failed: ${e.message}")
            }
        }
    }

    private fun serveDirectory(out: OutputStream, dir: File, urlPath: String) {
        val entries = dir.listFiles()?.sortedWith(
            compareBy({ !it.isDirectory }, { it.name.lowercase() })
        ) ?: emptyList()
        val html = buildString {
            append("<!doctype html><html><head><meta charset=\"utf-8\">")
            append("<title>GlassHole share — ${escapeHtml(dir.name)}</title>")
            // Glass screens are tiny — keep type readable and tap
            // targets large.
            append("<style>body{font-family:sans-serif;background:#000;color:#fff;font-size:18px;padding:14px}")
            append("a{color:#7ab8ff;display:block;padding:14px 4px;border-bottom:1px solid #222;text-decoration:none}")
            append("a:active{background:#222}")
            append(".dir::before{content:\"📁 \"}.file::before{content:\"📄 \"}.up::before{content:\"⬆ \"}")
            append(".size{color:#888;font-size:14px}</style></head><body>")
            append("<h2>${escapeHtml(if (urlPath == "/" || urlPath.isEmpty()) "/" else urlPath)}</h2>")
            if (urlPath != "/" && urlPath.isNotEmpty()) {
                append("<a class=\"up\" href=\"../\">up one level</a>")
            }
            if (entries.isEmpty()) {
                append("<p style=\"color:#888\">Empty directory.</p>")
            }
            for (entry in entries) {
                val nameEsc = escapeHtml(entry.name)
                val link = URLEncoder.encode(entry.name, "UTF-8").replace("+", "%20") +
                    if (entry.isDirectory) "/" else ""
                val cls = if (entry.isDirectory) "dir" else "file"
                val sizeLabel = if (entry.isDirectory) "" else
                    " <span class=\"size\">(${humanSize(entry.length())})</span>"
                append("<a class=\"$cls\" href=\"$link\">$nameEsc$sizeLabel</a>")
            }
            append("</body></html>")
        }
        val body = html.toByteArray(Charsets.UTF_8)
        out.write((
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Connection: close\r\n\r\n"
            ).toByteArray())
        out.write(body)
        out.flush()
    }

    private fun serveFile(out: OutputStream, file: File) {
        val mime = guessMime(file.name)
        out.write((
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: $mime\r\n" +
                "Content-Length: ${file.length()}\r\n" +
                "Content-Disposition: attachment; filename=\"${file.name}\"\r\n" +
                "Connection: close\r\n\r\n"
            ).toByteArray())
        file.inputStream().use { it.copyTo(out) }
        out.flush()
    }

    private fun writeStatus(out: OutputStream, code: Int, msg: String) {
        try {
            val body = "$code $msg\n".toByteArray()
            out.write((
                "HTTP/1.1 $code $msg\r\n" +
                    "Content-Type: text/plain; charset=utf-8\r\n" +
                    "Content-Length: ${body.size}\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray())
            out.write(body)
            out.flush()
        } catch (_: IOException) {}
    }

    private fun escapeHtml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun humanSize(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB")
        var size = bytes.toDouble()
        var i = 0
        while (size >= 1024 && i < units.size - 1) { size /= 1024; i++ }
        return if (i == 0) "${bytes} B" else String.format("%.1f %s", size, units[i])
    }

    private fun guessMime(filename: String): String {
        val lower = filename.lowercase()
        return when {
            lower.endsWith(".apk") -> "application/vnd.android.package-archive"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".txt") || lower.endsWith(".log") -> "text/plain; charset=utf-8"
            lower.endsWith(".html") || lower.endsWith(".htm") -> "text/html; charset=utf-8"
            lower.endsWith(".json") -> "application/json; charset=utf-8"
            lower.endsWith(".zip") -> "application/zip"
            else -> "application/octet-stream"
        }
    }

    /** Find an IPv4 address to advertise. Tries WifiManager first
     *  (works on older Android without permissions) and falls back to
     *  NetworkInterface enumeration when that returns 0 — which is the
     *  default on Android 12+ unless the app holds ACCESS_FINE_LOCATION.
     *  We deliberately prefer wlan/Wi-Fi-named interfaces but accept
     *  any non-loopback IPv4 as a fallback so the user can still get a
     *  URL when on hotspot / Ethernet / etc. */
    private fun wifiIp(): String? {
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val raw = wifi.connectionInfo?.ipAddress ?: 0
            if (raw != 0) return Formatter.formatIpAddress(raw)
        } catch (_: Exception) {}
        // Fallback: enumerate. Picks the first IPv4 on an interface
        // whose name looks like wireless; otherwise the first non-
        // loopback IPv4 anywhere.
        return try {
            val ifaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return null
            var fallback: String? = null
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback) continue
                val nameLower = iface.name.lowercase()
                val addrs = iface.inetAddresses
                for (addr in addrs) {
                    if (addr.isLoopbackAddress) continue
                    if (addr !is java.net.Inet4Address) continue
                    val host = addr.hostAddress ?: continue
                    if (nameLower.startsWith("wlan") || nameLower.startsWith("wifi") ||
                        nameLower.contains("eth")) return host
                    if (fallback == null) fallback = host
                }
            }
            fallback
        } catch (_: Exception) { null }
    }

    companion object { private const val TAG = "FileShareServer" }
}
