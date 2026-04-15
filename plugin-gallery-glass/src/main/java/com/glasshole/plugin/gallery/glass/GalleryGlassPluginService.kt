package com.glasshole.plugin.gallery.glass

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import com.glasshole.glass.sdk.GlassPluginMessage
import com.glasshole.glass.sdk.GlassPluginService
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * Scans the glass device for photos and videos and streams them to the phone
 * on request. Runs entirely in response to phone-issued plugin messages:
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
 */
class GalleryGlassPluginService : GlassPluginService() {

    companion object {
        private const val TAG = "GalleryGlassPlugin"
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

    override val pluginId: String = "gallery"

    // id → path so GET_FULL / DELETE can resolve without round-tripping the full path
    private val mediaIndex = mutableMapOf<String, String>()

    override fun onMessageFromPhone(message: GlassPluginMessage) {
        Log.d(TAG, "Message from phone: type=${message.type}")
        when (message.type) {
            "LIST_REQ" -> handleList()
            "GET_FULL" -> handleGetFull(message.payload)
            "DELETE" -> handleDelete(message.payload)
            else -> Log.w(TAG, "Unknown message type: ${message.type}")
        }
    }

    // --- LIST ---

    private fun handleList() {
        Thread {
            try {
                val items = JSONArray()
                mediaIndex.clear()
                scanMedia().forEach { file ->
                    val id = shortId(file.absolutePath)
                    mediaIndex[id] = file.absolutePath
                    val ext = file.extension.lowercase()
                    val type = when {
                        ext in IMAGE_EXTS -> "image"
                        ext in VIDEO_EXTS -> "video"
                        else -> "file"
                    }
                    val obj = JSONObject().apply {
                        put("id", id)
                        put("name", file.name)
                        put("type", type)
                        put("size", file.length())
                        put("ts", file.lastModified())
                        put("path", file.absolutePath)
                        put("thumb", generateThumb(file, type))
                    }
                    items.put(obj)
                }
                // Newest first
                val sorted = JSONArray()
                val sortable = (0 until items.length()).map { items.getJSONObject(it) }
                    .sortedByDescending { it.optLong("ts", 0L) }
                sortable.forEach { sorted.put(it) }

                val json = JSONObject().apply { put("items", sorted) }.toString()
                sendToPhone(GlassPluginMessage("LIST", json))
                Log.i(TAG, "Sent LIST with ${sorted.length()} items")
            } catch (e: Exception) {
                Log.e(TAG, "LIST failed: ${e.message}")
            }
        }.apply { isDaemon = true; start() }
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
            val bitmap = when (type) {
                "image" -> decodeScaledImage(file, THUMB_PX)
                "video" -> ThumbnailUtils.createVideoThumbnail(
                    file.absolutePath, MediaStore.Images.Thumbnails.MINI_KIND
                )
                else -> null
            } ?: return ""

            val square = ThumbnailUtils.extractThumbnail(bitmap, THUMB_PX, THUMB_PX)
            if (square !== bitmap) bitmap.recycle()

            val stream = ByteArrayOutputStream()
            square.compress(Bitmap.CompressFormat.JPEG, 70, stream)
            square.recycle()
            Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "thumb failed for ${file.name}: ${e.message}")
            ""
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

    private fun handleGetFull(payload: String) {
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
                        sendToPhone(GlassPluginMessage("CHUNK", chunk))
                        offset += read
                    }
                }
                sendChunkEnd(id)
                Log.i(TAG, "Sent $total bytes for $id")
            } catch (e: Exception) {
                Log.e(TAG, "GET_FULL failed: ${e.message}")
            }
        }.apply { isDaemon = true; start() }
    }

    private fun sendChunkEnd(id: String) {
        val end = JSONObject().apply { put("id", id) }.toString()
        sendToPhone(GlassPluginMessage("END", end))
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
            sendToPhone(GlassPluginMessage("DELETE_ACK", ack))
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
