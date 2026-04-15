package com.glasshole.phone.plugins.gallery

import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.glasshole.phone.R
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GalleryActivity : AppCompatActivity() {

    private lateinit var grid: GridView
    private lateinit var statusText: TextView
    private lateinit var refreshButton: Button
    private lateinit var adapter: ThumbAdapter

    private var items: List<GalleryItem> = emptyList()
    private val dateFmt = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault())

    private var currentProgress: ProgressDialog? = null
    private var currentDownloadId: String? = null

    private enum class PendingAction { OPEN, SAVE }
    private var currentDownloadAction: PendingAction = PendingAction.OPEN

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)

        grid = findViewById(R.id.galleryGrid)
        statusText = findViewById(R.id.galleryStatusText)
        refreshButton = findViewById(R.id.galleryRefreshButton)

        adapter = ThumbAdapter()
        grid.adapter = adapter

        refreshButton.setOnClickListener { refresh() }

        grid.setOnItemClickListener { _, _, position, _ ->
            openFull(items[position])
        }
        grid.setOnItemLongClickListener { _, _, position, _ ->
            showItemMenu(items[position])
            true
        }
    }

    override fun onStart() {
        super.onStart()
        val plugin = GalleryPlugin.instance
        if (plugin == null) {
            statusText.text = "GlassHole service not ready"
            return
        }

        plugin.onListChanged = { list -> runOnUiThread { onListUpdated(list) } }
        plugin.onDownloadProgress = { id, received, total ->
            runOnUiThread {
                if (id == currentDownloadId) {
                    val pct = if (total > 0) ((received * 100) / total).toInt() else 0
                    currentProgress?.progress = pct
                    currentProgress?.setMessage("$pct%  (${received / 1024} / ${total / 1024} KB)")
                }
            }
        }
        plugin.onDownloadComplete = { id, file ->
            runOnUiThread {
                if (id == currentDownloadId) {
                    currentProgress?.dismiss()
                    currentProgress = null
                    val item = items.firstOrNull { it.id == id }
                    val action = currentDownloadAction
                    currentDownloadId = null
                    currentDownloadAction = PendingAction.OPEN
                    when (action) {
                        PendingAction.OPEN -> launchViewer(file, item)
                        PendingAction.SAVE -> saveToPhone(file, item)
                    }
                }
            }
        }
        plugin.onDeleteResult = { id, ok ->
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (ok) "Deleted" else "Delete failed",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        // Pre-populate if we already have a list, then refresh to pull latest
        plugin.items.takeIf { it.isNotEmpty() }?.let(::onListUpdated)
        refresh()
    }

    override fun onStop() {
        super.onStop()
        GalleryPlugin.instance?.onListChanged = null
        GalleryPlugin.instance?.onDownloadProgress = null
        GalleryPlugin.instance?.onDownloadComplete = null
        GalleryPlugin.instance?.onDeleteResult = null
    }

    private fun refresh() {
        val sent = GalleryPlugin.instance?.requestList() ?: false
        statusText.text = if (sent) "Loading gallery..." else "Glass not connected"
    }

    private fun onListUpdated(list: List<GalleryItem>) {
        items = list
        adapter.notifyDataSetChanged()
        statusText.text = if (list.isEmpty())
            "No media on glass yet"
        else
            "${list.size} items — tap to open, long-press to delete"
    }

    private fun openFull(item: GalleryItem) {
        downloadOrRun(item, PendingAction.OPEN)
    }

    private fun downloadAndSave(item: GalleryItem) {
        downloadOrRun(item, PendingAction.SAVE)
    }

    private fun downloadOrRun(item: GalleryItem, action: PendingAction) {
        val plugin = GalleryPlugin.instance ?: return
        val existing = plugin.cachedFile(item)
        if (existing.exists() && existing.length() == item.size) {
            when (action) {
                PendingAction.OPEN -> launchViewer(existing, item)
                PendingAction.SAVE -> saveToPhone(existing, item)
            }
            return
        }
        val progress = ProgressDialog(this).apply {
            setTitle("Downloading ${item.name}")
            setMessage("0%")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            setCancelable(true)
            show()
        }
        currentProgress = progress
        currentDownloadId = item.id
        currentDownloadAction = action
        if (!plugin.requestFull(item.id)) {
            progress.dismiss()
            currentProgress = null
            currentDownloadId = null
            Toast.makeText(this, "Glass not connected", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showItemMenu(item: GalleryItem) {
        val options = arrayOf("Open", "Save to phone", "Delete")
        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openFull(item)
                    1 -> downloadAndSave(item)
                    2 -> confirmDelete(item)
                }
            }
            .show()
    }

    private fun saveToPhone(file: File, item: GalleryItem?) {
        val name = item?.name ?: file.name
        val type = item?.type ?: if (name.endsWith(".mp4", true)) "video" else "image"
        val mime = when (type) {
            "video" -> when (name.substringAfterLast('.', "").lowercase()) {
                "mp4" -> "video/mp4"; "mov" -> "video/quicktime"; "3gp" -> "video/3gpp"
                "mkv" -> "video/x-matroska"; "webm" -> "video/webm"; else -> "video/*"
            }
            else -> when (name.substringAfterLast('.', "").lowercase()) {
                "png" -> "image/png"; "webp" -> "image/webp"; "heic", "heif" -> "image/heic"
                else -> "image/jpeg"
            }
        }

        try {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val relPath = if (type == "video") Environment.DIRECTORY_MOVIES + "/GlassHole"
                              else Environment.DIRECTORY_PICTURES + "/GlassHole"
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                }
                val collection = if (type == "video")
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                contentResolver.insert(collection, values)
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(
                    if (type == "video") Environment.DIRECTORY_MOVIES
                    else Environment.DIRECTORY_PICTURES
                ).resolve("GlassHole")
                dir.mkdirs()
                val out = File(dir, name)
                FileInputStream(file).use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
                Uri.fromFile(out)
            }
            if (uri == null) {
                Toast.makeText(this, "Save failed — couldn't create entry", Toast.LENGTH_LONG).show()
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentResolver.openOutputStream(uri)?.use { output ->
                    FileInputStream(file).use { it.copyTo(output) }
                }
            }
            val where = if (type == "video") "Movies/GlassHole" else "Pictures/GlassHole"
            Toast.makeText(this, "Saved to $where", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun launchViewer(file: File, item: GalleryItem?) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                this, "${packageName}.fileprovider", file
            )
            val mime = when (item?.type) {
                "video" -> "video/*"
                else -> "image/*"
            }
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(view, "Open ${item?.name ?: "file"}"))
        } catch (e: Exception) {
            Toast.makeText(this, "Open failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmDelete(item: GalleryItem) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${item.name}?")
            .setMessage("This permanently removes the file from the glass.")
            .setPositiveButton("Delete") { _, _ ->
                val sent = GalleryPlugin.instance?.delete(item.id) ?: false
                if (!sent) Toast.makeText(this, "Glass not connected", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private inner class ThumbAdapter : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(this@GalleryActivity)
                .inflate(R.layout.item_gallery_thumb, parent, false)
            val item = items[position]

            val image = view.findViewById<ImageView>(R.id.thumbImage)
            val badge = view.findViewById<TextView>(R.id.thumbBadge)
            val nameText = view.findViewById<TextView>(R.id.thumbName)

            val bmp = decodeThumb(item.thumbBase64)
            if (bmp != null) image.setImageBitmap(bmp)
            else image.setImageResource(android.R.drawable.ic_menu_gallery)

            badge.visibility = if (item.type == "video") View.VISIBLE else View.GONE
            nameText.text = "${formatSize(item.size)} · ${dateFmt.format(Date(item.timestamp))}"

            return view
        }

        private fun decodeThumb(base64: String): android.graphics.Bitmap? {
            if (base64.isEmpty()) return null
            return try {
                val bytes = Base64.decode(base64, Base64.NO_WRAP)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (_: Exception) { null }
        }

        private fun formatSize(bytes: Long): String {
            return when {
                bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
                bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
                else -> "$bytes B"
            }
        }
    }
}
