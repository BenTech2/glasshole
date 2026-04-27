package com.glasshole.glassee2.home

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONObject

/**
 * Process-wide store of currently-active notifications the phone has
 * forwarded. BluetoothListenerService pushes entries in on NOTIF:
 * messages and removes them on NOTIF_REMOVED:. HomeActivity listens
 * for changes and repaints its notification card / drawer.
 *
 * Order is insertion order (newest last) so the card's "latest" is
 * just the last entry.
 */
object NotificationStore {

    data class Entry(
        val key: String,
        val app: String,
        val title: String,
        val text: String,
        val iconBitmap: Bitmap?,
        val pictureBitmap: Bitmap?,
        val actions: List<NotifAction>,
        val rawJson: String,
        val timestamp: Long
    )

    private val entries = linkedMapOf<String, Entry>()
    private val listeners = mutableListOf<() -> Unit>()

    @Synchronized
    fun put(json: String) {
        val parsed = try { JSONObject(json) } catch (_: Exception) { return }
        val key = parsed.optString("key", "").ifEmpty { return }
        entries.remove(key) // re-insert to move to end (most recent)
        entries[key] = Entry(
            key = key,
            app = parsed.optString("app", parsed.optString("pkg", "")),
            title = parsed.optString("title", ""),
            text = parsed.optString("text", ""),
            iconBitmap = decodeIcon(parsed.optString("icon", "")),
            pictureBitmap = decodeIcon(parsed.optString("picture", "")),
            actions = NotifAction.parseArray(parsed.optJSONArray("actions")?.toString()),
            rawJson = json,
            timestamp = System.currentTimeMillis()
        )
        fire()
    }

    @Synchronized
    fun remove(key: String) {
        if (entries.remove(key) != null) fire()
    }

    @Synchronized
    fun all(): List<Entry> = entries.values.toList()

    @Synchronized
    fun latest(): Entry? = entries.values.lastOrNull()

    @Synchronized
    fun count(): Int = entries.size

    @Synchronized
    fun addListener(l: () -> Unit) { listeners.add(l) }

    @Synchronized
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    private fun fire() {
        // Copy to avoid ConcurrentModificationException if a listener mutates.
        val snapshot = listeners.toList()
        for (l in snapshot) try { l() } catch (_: Exception) {}
    }

    private fun decodeIcon(b64: String): Bitmap? {
        if (b64.isEmpty()) return null
        return try {
            val clean = b64.replace("\\", "")
            val bytes = Base64.decode(clean, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) { null }
    }
}
