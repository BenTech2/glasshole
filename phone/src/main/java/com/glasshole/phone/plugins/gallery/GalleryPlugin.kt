package com.glasshole.phone.plugins.gallery

import android.content.Context
import android.util.Base64
import android.util.Log
import com.glasshole.phone.AppLog
import com.glasshole.phone.plugin.PhonePlugin
import com.glasshole.phone.plugin.PluginSender
import com.glasshole.sdk.PluginMessage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

data class GalleryItem(
    val id: String,
    val name: String,
    val type: String,          // "image" or "video"
    val size: Long,
    val timestamp: Long,
    val path: String,
    /** base64 JPEG, 120×120 square */
    val thumbBase64: String
)

class GalleryPlugin : PhonePlugin {

    companion object {
        private const val TAG = "GalleryPlugin"
        @Volatile
        var instance: GalleryPlugin? = null
            private set
    }

    override val pluginId: String = "gallery"

    private lateinit var appContext: Context
    private lateinit var sender: PluginSender

    @Volatile
    var items: List<GalleryItem> = emptyList()
        private set

    // id → FileOutputStream for chunked downloads in progress
    private val downloads = ConcurrentHashMap<String, DownloadState>()

    var onListChanged: ((List<GalleryItem>) -> Unit)? = null
    var onDownloadProgress: ((id: String, received: Long, total: Long) -> Unit)? = null
    var onDownloadComplete: ((id: String, file: File) -> Unit)? = null
    var onDeleteResult: ((id: String, ok: Boolean) -> Unit)? = null

    override fun onCreate(context: Context, sender: PluginSender) {
        this.appContext = context.applicationContext
        this.sender = sender
        instance = this
        cacheDir().mkdirs()
    }

    override fun onDestroy() {
        instance = null
        downloads.values.forEach { try { it.out.close() } catch (_: Exception) {} }
        downloads.clear()
    }

    override fun onMessageFromGlass(message: PluginMessage) {
        when (message.type) {
            "LIST" -> handleList(message.payload)
            "CHUNK" -> handleChunk(message.payload)
            "END" -> handleEnd(message.payload)
            "DELETE_ACK" -> handleDeleteAck(message.payload)
            else -> Log.d(TAG, "Unknown message: ${message.type}")
        }
    }

    // --- outbound ---

    fun requestList(): Boolean {
        AppLog.log("Gallery", "Requesting media list from glass")
        return sender(PluginMessage("LIST_REQ", ""))
    }

    fun requestFull(id: String): Boolean {
        val item = items.firstOrNull { it.id == id }
        AppLog.log(
            "Gallery",
            "Requesting full: ${item?.name ?: id} (${(item?.size ?: 0L) / 1024} KB)"
        )
        val payload = JSONObject().apply { put("id", id) }.toString()
        return sender(PluginMessage("GET_FULL", payload))
    }

    fun delete(id: String): Boolean {
        val item = items.firstOrNull { it.id == id }
        AppLog.log("Gallery", "Deleting: ${item?.name ?: id}")
        val payload = JSONObject().apply { put("id", id) }.toString()
        return sender(PluginMessage("DELETE", payload))
    }

    fun cachedFile(item: GalleryItem): File {
        return File(cacheDir(), "${item.id}_${item.name}")
    }

    private fun cacheDir(): File = File(appContext.cacheDir, "gallery")

    // --- inbound ---

    private fun handleList(payload: String) {
        try {
            val obj = JSONObject(payload)
            val arr = obj.optJSONArray("items") ?: JSONArray()
            val list = mutableListOf<GalleryItem>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    GalleryItem(
                        id = o.getString("id"),
                        name = o.optString("name", "unknown"),
                        type = o.optString("type", "file"),
                        size = o.optLong("size", 0L),
                        timestamp = o.optLong("ts", 0L),
                        path = o.optString("path", ""),
                        thumbBase64 = o.optString("thumb", "")
                    )
                )
            }
            items = list
            onListChanged?.invoke(list)
            Log.i(TAG, "Gallery list: ${list.size} items")
            AppLog.log("Gallery", "Received list: ${list.size} items")
        } catch (e: Exception) {
            Log.e(TAG, "LIST parse failed: ${e.message}")
        }
    }

    private fun handleChunk(payload: String) {
        try {
            val obj = JSONObject(payload)
            val id = obj.getString("id")
            val offset = obj.optLong("offset", 0L)
            val total = obj.optLong("total", 0L)
            val data = obj.getString("data")
            val bytes = Base64.decode(data, Base64.NO_WRAP)

            val item = items.firstOrNull { it.id == id }
            if (item == null) {
                Log.w(TAG, "CHUNK for unknown id $id")
                return
            }
            val state = downloads.getOrPut(id) {
                val file = cachedFile(item)
                file.parentFile?.mkdirs()
                DownloadState(file, FileOutputStream(file), total)
            }
            state.out.write(bytes)
            state.received = offset + bytes.size
            state.total = total
            onDownloadProgress?.invoke(id, state.received, total)
        } catch (e: Exception) {
            Log.e(TAG, "CHUNK write failed: ${e.message}")
        }
    }

    private fun handleEnd(payload: String) {
        try {
            val id = JSONObject(payload).getString("id")
            val state = downloads.remove(id) ?: return
            try { state.out.flush() } catch (_: Exception) {}
            try { state.out.close() } catch (_: Exception) {}
            val item = items.firstOrNull { it.id == id }
            Log.i(TAG, "Download complete: $id (${state.file.length()} bytes)")
            AppLog.log(
                "Gallery",
                "Download complete: ${item?.name ?: id} (${state.file.length() / 1024} KB)"
            )
            onDownloadComplete?.invoke(id, state.file)
        } catch (e: Exception) {
            Log.e(TAG, "END failed: ${e.message}")
        }
    }

    private fun handleDeleteAck(payload: String) {
        try {
            val obj = JSONObject(payload)
            val id = obj.getString("id")
            val ok = obj.optBoolean("ok", false)
            AppLog.log("Gallery", "Delete ack: $id ok=$ok")
            if (ok) {
                items = items.filterNot { it.id == id }
                onListChanged?.invoke(items)
            }
            onDeleteResult?.invoke(id, ok)
        } catch (_: Exception) {}
    }

    private class DownloadState(
        val file: File,
        val out: FileOutputStream,
        var total: Long
    ) {
        var received: Long = 0
    }
}
