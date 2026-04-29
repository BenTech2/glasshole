package com.glasshole.glassee2

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.net.wifi.WifiManager
import android.provider.MediaStore
import android.text.format.Formatter
import android.util.Base64
import android.util.Log
import com.glasshole.glass.sdk.GalleryHttpServer
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * Lifted from the retired plugin-gallery-glass APK. Scans the glass
 * filesystem for photos and videos and streams them to the phone in
 * response to plugin messages tagged with pluginId="gallery".
 *
 *   phone → glass:
 *     LIST_REQ                      — enumerate media
 *     GET_FULL   { id }             — send a full file in chunks
 *     DELETE     { id }             — delete a file
 *
 *   glass → phone:
 *     LIST       { items: [...] }   — one entry per file with a base64 thumb
 *     CHUNK      { id, offset, total, data }
 *     END        { id }
 *     DELETE_ACK { id, ok }
 *
 * Not a Service — just a plain helper owned by BluetoothListenerService.
 * Responses go out via the injected [send] callback which hits the BT
 * pipe using the normal plugin-message framing.
 */
class GalleryHandler(
    private val context: Context,
    private val send: (type: String, payload: String) -> Unit
) {

    companion object {
        private const val TAG = "GalleryHandler"
        private const val THUMB_PX = 120
        private const val CHUNK_BYTES = 48 * 1024
        private val SCAN_DIRS = listOf(
            "/sdcard/Pictures",
            "/sdcard/Movies",
            "/sdcard/DCIM"
        )
        private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "heic", "heif")
        private val VIDEO_EXTS = setOf("mp4", "mov", "3gp", "mkv", "webm", "avi")
    }

    // id → path so GET_FULL / DELETE can resolve without round-tripping the full path
    private val mediaIndex = mutableMapOf<String, String>()

    /** Persistent HTTP server. Lazily started on the first LIST_REQ
     *  when WiFi is up; reused for the lifetime of the process. The
     *  server resolves ids by calling back into [mediaIndex] and
     *  accepts uploads via [acceptUpload]. */
    private val httpServer by lazy {
        GalleryHttpServer(
            resolveFile = { id -> mediaIndex[id]?.let { File(it) }?.takeIf { it.exists() } },
            generateThumbBytes = { file -> generateThumbBytes(file) },
            acceptUpload = { name, type, staged -> acceptUpload(name, type, staged) }
        )
    }
    @Volatile private var httpServerStarted = false

    /** Active BT upload session, if any. Single-flight — concurrent
     *  uploads aren't supported on the BT pipe. */
    @Volatile private var btUpload: BtUploadSession? = null

    private class BtUploadSession(
        val name: String,
        val type: String,
        val expectedSize: Long,
        val expectedMd5: String?,
        val target: File,
        val out: java.io.FileOutputStream
    ) {
        var received: Long = 0
        val md5 = java.security.MessageDigest.getInstance("MD5")
    }

    fun handleMessage(type: String, payload: String) {
        Log.d(TAG, "Message from phone: type=$type")
        when (type) {
            "LIST_REQ" -> handleList()
            // Default GET_FULL prefers WiFi when available; phone can
            // re-issue with GET_FULL_BT to force the BT chunk path.
            "GET_FULL" -> handleGetFull(payload, allowWifi = true)
            "GET_FULL_BT" -> handleGetFull(payload, allowWifi = false)
            "DELETE" -> handleDelete(payload)
            "UPLOAD_OFFER" -> handleUploadOffer()
            "UPLOAD_START" -> handleUploadStart(payload)
            "UPLOAD_DATA" -> handleUploadData(payload)
            "UPLOAD_END" -> handleUploadEnd(payload)
            else -> Log.w(TAG, "Unknown message type: $type")
        }
    }

    // --- LIST ---

    private fun handleList() {
        Thread {
            try {
                mediaIndex.clear()
                val files = scanMedia()
                val typedFiles = files.map { file ->
                    val id = shortId(file.absolutePath)
                    mediaIndex[id] = file.absolutePath
                    val ext = file.extension.lowercase()
                    val type = when {
                        ext in IMAGE_EXTS -> "image"
                        ext in VIDEO_EXTS -> "video"
                        else -> "file"
                    }
                    Triple(id, type, file)
                }

                // Decide whether to advertise WiFi-LAN URLs alongside the
                // inline base64 thumbs. If the glass is on WiFi we start
                // (idempotent) the persistent HTTP server and include
                // /file and /thumb URLs per item. The base64 thumb stays
                // in the payload as a fallback for phones that can't
                // reach the LAN URL — small enough that the belt-and-
                // suspenders cost is acceptable.
                val ip = wifiIpString()
                val wifiAvailable = ip != null
                if (wifiAvailable) {
                    if (!httpServerStarted) {
                        httpServer.start()
                        httpServerStarted = true
                    }
                }

                val items = JSONArray()
                for ((id, type, file) in typedFiles) {
                    val obj = JSONObject().apply {
                        put("id", id)
                        put("name", file.name)
                        put("type", type)
                        put("size", file.length())
                        put("ts", file.lastModified())
                        put("path", file.absolutePath)
                        if (wifiAvailable && ip != null) {
                            // WiFi path: skip the base64 thumb entirely —
                            // phone fetches via /thumb HTTP. Saves 56 ×
                            // ~5KB = ~280KB on the BT pipe per LIST and
                            // makes the list response near-instant.
                            put("thumb_url", httpServer.thumbUrl(ip, id))
                            put("file_url", httpServer.fileUrl(ip, id))
                        } else {
                            put("thumb", generateThumb(file, type))
                        }
                    }
                    items.put(obj)
                }

                val sorted = JSONArray()
                val sortable = (0 until items.length()).map { items.getJSONObject(it) }
                    .sortedByDescending { it.optLong("ts", 0L) }
                sortable.forEach { sorted.put(it) }

                val rootJson = JSONObject().apply {
                    put("items", sorted)
                    if (wifiAvailable && ip != null) {
                        put("wifi_base_url", "http://$ip:${httpServer.port}")
                    }
                }.toString()
                send("LIST", rootJson)
                Log.i(TAG, "Sent LIST with ${sorted.length()} items, wifi=$wifiAvailable")
            } catch (e: Exception) {
                Log.e(TAG, "LIST failed: ${e.message}")
            }
        }.apply { isDaemon = true; start() }
    }

    private fun wifiIpString(): String? {
        return try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ip = wifi.connectionInfo?.ipAddress ?: 0
            if (ip == 0) null else Formatter.formatIpAddress(ip)
        } catch (_: Exception) { null }
    }

    private fun scanMedia(): List<File> {
        val results = mutableListOf<File>()
        for (path in SCAN_DIRS) {
            val root = File(path)
            if (!root.isDirectory) continue
            walk(root, results, maxDepth = 3)
        }
        return results
    }

    private fun walk(dir: File, out: MutableList<File>, maxDepth: Int) {
        if (maxDepth < 0) return
        val children = try { dir.listFiles() } catch (_: Exception) { null } ?: return
        for (child in children) {
            if (child.isDirectory) {
                if (child.name.startsWith(".")) continue  // skip .thumbnails etc
                walk(child, out, maxDepth - 1)
            } else {
                val ext = child.extension.lowercase()
                if (ext in IMAGE_EXTS || ext in VIDEO_EXTS) out.add(child)
            }
        }
    }

    private fun generateThumb(file: File, type: String): String {
        return try {
            val bytes = generateThumbBytes(file, type) ?: return ""
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "thumb base64 failed for ${file.name}: ${e.message}")
            ""
        }
    }

    /** Auto-detect type from extension. Used by the HTTP server, which
     *  doesn't track type per id. */
    private fun generateThumbBytes(file: File): ByteArray? {
        val ext = file.extension.lowercase()
        val type = when {
            ext in IMAGE_EXTS -> "image"
            ext in VIDEO_EXTS -> "video"
            else -> return null
        }
        return generateThumbBytes(file, type)
    }

    private fun generateThumbBytes(file: File, type: String): ByteArray? {
        return try {
            val bitmap = when (type) {
                "image" -> decodeScaledImage(file, THUMB_PX)
                "video" -> ThumbnailUtils.createVideoThumbnail(
                    file.absolutePath, MediaStore.Images.Thumbnails.MINI_KIND
                )
                else -> null
            } ?: return null

            val square = ThumbnailUtils.extractThumbnail(bitmap, THUMB_PX, THUMB_PX)
            if (square !== bitmap) bitmap.recycle()

            val stream = ByteArrayOutputStream()
            square.compress(Bitmap.CompressFormat.JPEG, 70, stream)
            square.recycle()
            stream.toByteArray()
        } catch (e: Exception) {
            Log.w(TAG, "thumb failed for ${file.name}: ${e.message}")
            null
        }
    }

    private fun decodeScaledImage(file: File, targetPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > targetPx * 2 && bounds.outHeight / sample > targetPx * 2) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    // --- GET_FULL ---

    private fun handleGetFull(payload: String, allowWifi: Boolean) {
        Thread {
            try {
                val id = JSONObject(payload).getString("id")
                val path = mediaIndex[id] ?: run {
                    Log.w(TAG, "GET_FULL: unknown id $id")
                    sendChunkEnd(id)
                    return@Thread
                }
                val file = File(path)
                if (!file.exists()) {
                    sendChunkEnd(id)
                    return@Thread
                }

                if (allowWifi) {
                    val ip = wifiIpString()
                    if (ip != null) {
                        if (!httpServerStarted) {
                            httpServer.start()
                            httpServerStarted = true
                        }
                        val url = httpServer.fileUrl(ip, id)
                        val offer = JSONObject().apply {
                            put("id", id)
                            put("url", url)
                            put("size", file.length())
                            put("name", file.name)
                        }.toString()
                        send("WIFI_OFFER", offer)
                        Log.i(TAG, "WIFI_OFFER for $id at $url")
                        return@Thread
                    }
                    Log.i(TAG, "WiFi unavailable, falling back to BT chunks for $id")
                }

                val total = file.length()
                file.inputStream().use { input ->
                    val buffer = ByteArray(CHUNK_BYTES)
                    var offset = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        val slice = if (read == buffer.size) buffer else buffer.copyOfRange(0, read)
                        val b64 = Base64.encodeToString(slice, Base64.NO_WRAP)
                        val chunk = JSONObject().apply {
                            put("id", id)
                            put("offset", offset)
                            put("total", total)
                            put("data", b64)
                        }.toString()
                        send("CHUNK", chunk)
                        offset += read
                    }
                }
                sendChunkEnd(id)
                Log.i(TAG, "Sent $total bytes for $id (BT)")
            } catch (e: Exception) {
                Log.e(TAG, "GET_FULL failed: ${e.message}")
            }
        }.apply { isDaemon = true; start() }
    }

    private fun sendChunkEnd(id: String) {
        val end = JSONObject().apply { put("id", id) }.toString()
        send("END", end)
    }

    // --- DELETE ---

    private fun handleDelete(payload: String) {
        try {
            val id = JSONObject(payload).getString("id")
            val path = mediaIndex[id]
            val ok = if (path != null) {
                val f = File(path)
                val result = f.delete()
                if (result) mediaIndex.remove(id)
                result
            } else false
            val ack = JSONObject().apply {
                put("id", id)
                put("ok", ok)
            }.toString()
            send("DELETE_ACK", ack)
            Log.i(TAG, "Deleted $id: $ok")
        } catch (e: Exception) {
            Log.e(TAG, "DELETE failed: ${e.message}")
        }
    }

    // --- UPLOAD ---
    //
    // Two transports, mirroring the download path:
    //   • WiFi: phone POSTs to `httpServer.uploadUrl(...)`, the
    //     `acceptUpload` callback below does the file move + index
    //     refresh, and the response is the per-file ack.
    //   • BT:   phone sends UPLOAD_START → UPLOAD_DATA × N → UPLOAD_END.
    //     We persist chunks under [btUpload] and reply UPLOAD_ACK on
    //     END (or on early failure).

    private fun handleUploadOffer() {
        val ip = wifiIpString()
        if (ip != null) {
            if (!httpServerStarted) {
                httpServer.start()
                httpServerStarted = true
            }
            val json = JSONObject().apply {
                put("wifi", true)
                put("url", httpServer.uploadUrl(ip))
            }.toString()
            send("UPLOAD_READY", json)
            Log.i(TAG, "UPLOAD_READY (wifi at ${httpServer.uploadUrl(ip)})")
        } else {
            val json = JSONObject().apply { put("wifi", false) }.toString()
            send("UPLOAD_READY", json)
            Log.i(TAG, "UPLOAD_READY (BT only — wifi unavailable)")
        }
    }

    /**
     * Wi-Fi upload arrived as a fully-staged file. Move it into the
     * appropriate scanned directory so the next LIST picks it up.
     */
    private fun acceptUpload(name: String, type: String, staged: File): File? {
        return try {
            val target = chooseUploadTarget(name, type)
            target.parentFile?.mkdirs()
            // Try rename (zero-copy). If it crosses filesystems, fall
            // back to copy + delete.
            if (!staged.renameTo(target)) {
                staged.inputStream().use { input ->
                    target.outputStream().use { out -> input.copyTo(out) }
                }
                staged.delete()
            }
            // Refresh the in-memory index so a subsequent LIST_REQ
            // surfaces the new file even before a scan walk.
            mediaIndex[shortId(target.absolutePath)] = target.absolutePath
            Log.i(TAG, "Wi-Fi upload accepted: ${target.absolutePath}")
            target
        } catch (e: Exception) {
            Log.e(TAG, "acceptUpload failed: ${e.message}")
            try { staged.delete() } catch (_: Exception) {}
            null
        }
    }

    private fun chooseUploadTarget(rawName: String, type: String): File {
        val safeName = rawName
            .replace('/', '_').replace('\\', '_').replace(':', '_')
            .ifEmpty { "phone-upload-${System.currentTimeMillis()}" }
        // Land alongside the camera plugin's own captures, in /sdcard/DCIM/Camera —
        // matches what the user expects to see in the Glass gallery.
        val baseDir = "/sdcard/DCIM/Camera"
        var candidate = File(baseDir, safeName)
        if (!candidate.exists()) return candidate
        // Disambiguate "name (1).jpg", "name (2).jpg", ...
        val dot = safeName.lastIndexOf('.')
        val stem = if (dot > 0) safeName.substring(0, dot) else safeName
        val ext = if (dot > 0) safeName.substring(dot) else ""
        var n = 1
        while (true) {
            candidate = File(baseDir, "$stem ($n)$ext")
            if (!candidate.exists()) return candidate
            n++
        }
    }

    private fun handleUploadStart(payload: String) {
        try {
            val obj = JSONObject(payload)
            val name = obj.getString("name")
            val type = obj.optString("type", "image")
            val size = obj.optLong("size", 0L)
            val md5 = obj.optString("md5", "").ifEmpty { null }

            // If a previous session was still open (rare — would only
            // happen on a phone-side retry), close it cleanly.
            btUpload?.let { try { it.out.close() } catch (_: Exception) {} }

            val target = chooseUploadTarget(name, type)
            target.parentFile?.mkdirs()
            btUpload = BtUploadSession(
                name = name,
                type = type,
                expectedSize = size,
                expectedMd5 = md5,
                target = target,
                out = java.io.FileOutputStream(target)
            )
            Log.i(TAG, "BT UPLOAD_START: $name → ${target.absolutePath} ($size bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "UPLOAD_START failed: ${e.message}")
            sendUploadAck(payload.tryName(), ok = false, message = "start failed")
        }
    }

    private fun handleUploadData(payload: String) {
        val s = btUpload ?: return
        try {
            // Chunk format mirrors INSTALL_DATA — payload is base64.
            val bytes = android.util.Base64.decode(payload, android.util.Base64.NO_WRAP)
            s.out.write(bytes)
            s.received += bytes.size
            s.md5.update(bytes)
        } catch (e: Exception) {
            Log.e(TAG, "UPLOAD_DATA write failed: ${e.message}")
        }
    }

    private fun handleUploadEnd(payload: String) {
        val s = btUpload ?: run {
            Log.w(TAG, "UPLOAD_END without active session")
            return
        }
        btUpload = null
        try {
            try { s.out.flush() } catch (_: Exception) {}
            try { s.out.close() } catch (_: Exception) {}

            val gotMd5 = s.md5.digest().joinToString("") { "%02x".format(it) }
            val md5Ok = s.expectedMd5 == null || s.expectedMd5.equals(gotMd5, ignoreCase = true)
            val sizeOk = s.expectedSize == 0L || s.received == s.expectedSize
            if (!md5Ok || !sizeOk) {
                Log.e(TAG, "UPLOAD_END verification failed: " +
                    "size=${s.received}/${s.expectedSize} md5=$gotMd5/${s.expectedMd5}")
                try { s.target.delete() } catch (_: Exception) {}
                sendUploadAck(s.name, ok = false, message = "verification failed")
                return
            }
            mediaIndex[shortId(s.target.absolutePath)] = s.target.absolutePath
            Log.i(TAG, "BT upload complete: ${s.target.absolutePath} (${s.received} bytes)")
            sendUploadAck(s.name, ok = true, path = s.target.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "UPLOAD_END failed: ${e.message}")
            sendUploadAck(s.name, ok = false, message = e.message ?: "end failed")
        }
    }

    private fun sendUploadAck(name: String, ok: Boolean, path: String? = null, message: String? = null) {
        val obj = JSONObject().apply {
            put("name", name)
            put("ok", ok)
            if (path != null) put("path", path)
            if (message != null) put("message", message)
        }.toString()
        send("UPLOAD_ACK", obj)
    }

    /** Tiny helper for the START failure path where we only have the
     *  raw payload string in hand. */
    private fun String.tryName(): String = try {
        JSONObject(this).optString("name", "?")
    } catch (_: Exception) { "?" }

    private fun shortId(path: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(path.toByteArray())
        return bytes.take(8).joinToString("") { "%02x".format(it) }
    }
}
