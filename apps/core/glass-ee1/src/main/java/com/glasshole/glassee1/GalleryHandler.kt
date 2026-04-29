package com.glasshole.glassee1

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
     *  server resolves ids by calling back into [mediaIndex]. */
    private val httpServer by lazy {
        GalleryHttpServer(
            resolveFile = { id -> mediaIndex[id]?.let { File(it) }?.takeIf { it.exists() } },
            generateThumbBytes = { file -> generateThumbBytes(file) }
        )
    }
    @Volatile private var httpServerStarted = false

    fun handleMessage(type: String, payload: String) {
        Log.d(TAG, "Message from phone: type=$type")
        when (type) {
            "LIST_REQ" -> handleList()
            // Default GET_FULL prefers WiFi when available; phone can
            // re-issue with GET_FULL_BT to force the BT chunk path.
            "GET_FULL" -> handleGetFull(payload, allowWifi = true)
            "GET_FULL_BT" -> handleGetFull(payload, allowWifi = false)
            "DELETE" -> handleDelete(payload)
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

    private fun shortId(path: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(path.toByteArray())
        return bytes.take(8).joinToString("") { "%02x".format(it) }
    }
}
