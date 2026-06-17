package com.glasshole.phone.plugins.gallery

import android.app.Dialog
import android.content.ContentValues
import android.content.Intent
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
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
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.glasshole.phone.R
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class GalleryActivity : AppCompatActivity() {

    private lateinit var grid: GridView
    private lateinit var emptyView: TextView
    private lateinit var toolbar: MaterialToolbar
    private lateinit var listProgress: LinearProgressIndicator
    private lateinit var adapter: ThumbAdapter

    private var items: List<GalleryItem> = emptyList()
    private val dateFmt = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault())

    /** Active modal — for single-item open/save we show an indeterminate
     *  spinner; for the bulk save-zip run we show progress + detail. */
    private var currentDialog: Dialog? = null
    private var currentDialogMessage: TextView? = null
    private var currentDialogProgress: LinearProgressIndicator? = null
    private var currentDialogDetail: TextView? = null
    private var currentDownloadId: String? = null

    private enum class PendingAction { OPEN, SAVE, ZIP_QUEUE }
    private var currentDownloadAction: PendingAction = PendingAction.OPEN

    // --- Multi-select state ---

    /** Ordered selected ids — preserves the user's tap order so the
     *  zip output is stable. */
    private val selectedIds = LinkedHashSet<String>()
    private var inSelectionMode: Boolean = false

    // --- Bulk-action plumbing ---

    /** Queue of items to download for the active "save zip" run. The
     *  head of the queue is whatever GET_FULL is currently in flight;
     *  on END we pop it, append the file to [zipQueueFiles], and
     *  request the next. Empty when no zip run is active. */
    private val zipQueue: ArrayDeque<GalleryItem> = ArrayDeque()
    private val zipQueueFiles: MutableList<Pair<GalleryItem, File>> = mutableListOf()

    /** Tracks ids waiting on a DELETE_ACK during a bulk delete run.
     *  When this empties out, the delete batch is done. */
    private val pendingBulkDeletes: MutableSet<String> = mutableSetOf()

    // --- Upload state ---

    /** Worker thread for the active upload run, if any. */
    private var uploadThread: Thread? = null
    /** UI-side progress signalling for the upload run. */
    @Volatile private var uploadCompleted = 0
    @Volatile private var uploadTotal = 0
    /** Set when waiting for UPLOAD_ACK on the BT path. The worker
     *  thread sleeps on it; the plugin callback wakes it up. */
    private val btAckLatch = Object()
    @Volatile private var btAckPending: String? = null
    @Volatile private var btAckOk: Boolean = false
    @Volatile private var btAckMessage: String? = null
    /** Latch for UPLOAD_READY response. */
    private val readyLatch = Object()
    @Volatile private var readyResult: ReadyState? = null
    private data class ReadyState(val wifiUrl: String?)

    /** Picker for the system multi-select document picker. */
    private val pickMediaLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            val list = uris.orEmpty()
            if (list.isNotEmpty()) startUpload(list)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)

        grid = findViewById(R.id.galleryGrid)
        emptyView = findViewById(R.id.galleryEmptyView)
        toolbar = findViewById(R.id.galleryToolbar)
        listProgress = findViewById(R.id.galleryListProgress)
        renderToolbarForMode()

        adapter = ThumbAdapter()
        grid.adapter = adapter

        grid.setOnItemClickListener { _, _, position, _ ->
            val item = items.getOrNull(position) ?: return@setOnItemClickListener
            if (inSelectionMode) toggleSelected(item)
            else openFull(item)
        }
        grid.setOnItemLongClickListener { _, _, position, _ ->
            val item = items.getOrNull(position) ?: return@setOnItemLongClickListener true
            if (!inSelectionMode) {
                enterSelectionMode()
                toggleSelected(item)
            } else {
                // Long-press still shows the per-item menu mid-selection
                // — useful for opening / saving a specific item without
                // disturbing the selection.
                showItemMenu(item)
            }
            true
        }
    }

    /**
     * Configure the MaterialToolbar's title, navigation icon and menu
     * for the current mode. Called on every selection-state change so
     * the toolbar morphs in place.
     */
    private fun renderToolbarForMode() {
        toolbar.menu.clear()
        if (inSelectionMode) {
            toolbar.title = "${selectedIds.size} selected"
            toolbar.subtitle = null
            toolbar.setNavigationIcon(R.drawable.ic_close)
            toolbar.navigationContentDescription = "Cancel selection"
            toolbar.setNavigationOnClickListener { exitSelectionMode() }
            toolbar.inflateMenu(R.menu.gallery_selection_menu)
            val anySelected = selectedIds.isNotEmpty()
            toolbar.menu.findItem(R.id.galleryMenuSaveZip)?.isEnabled = anySelected
            toolbar.menu.findItem(R.id.galleryMenuDelete)?.isEnabled = anySelected
            toolbar.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.galleryMenuSelectAll -> { selectAll(); true }
                    R.id.galleryMenuSaveZip -> { startBulkSaveZip(); true }
                    R.id.galleryMenuDelete -> { confirmBulkDelete(); true }
                    else -> false
                }
            }
        } else {
            toolbar.title = "Glass gallery"
            toolbar.subtitle = subtitleForListState()
            toolbar.navigationIcon = null
            toolbar.setNavigationOnClickListener(null)
            toolbar.inflateMenu(R.menu.gallery_menu)
            toolbar.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.galleryMenuRefresh -> { refresh(); true }
                    R.id.galleryMenuUpload -> { launchUploadPicker(); true }
                    else -> false
                }
            }
        }
    }

    private fun subtitleForListState(): String {
        val transport = if (GalleryPlugin.instance?.wifiBaseUrl?.isNotEmpty() == true)
            "Wi-Fi LAN" else "Bluetooth"
        return when {
            items.isEmpty() -> transport
            else -> "${items.size} items · $transport"
        }
    }

    override fun onBackPressed() {
        if (inSelectionMode) {
            exitSelectionMode()
            return
        }
        super.onBackPressed()
    }

    override fun onStart() {
        super.onStart()
        val plugin = GalleryPlugin.instance
        if (plugin == null) {
            toolbar.subtitle = "Service not ready"
            return
        }

        plugin.onListChanged = { list -> runOnUiThread { onListUpdated(list) } }
        plugin.onDownloadProgress = { id, received, total ->
            runOnUiThread {
                if (id == currentDownloadId) {
                    val pct = if (total > 0) ((received * 100) / total).toInt() else 0
                    // Bulk run owns its own % calc — only update the bar
                    // for the single-item dialog flow.
                    if (currentDownloadAction != PendingAction.ZIP_QUEUE) {
                        currentDialogProgress?.progress = pct
                    }
                    currentDialogDetail?.text =
                        "$pct%  (${received / 1024} / ${total / 1024} KB)"
                }
            }
        }
        plugin.onDownloadComplete = { id, file ->
            runOnUiThread {
                if (id == currentDownloadId) {
                    val action = currentDownloadAction
                    val item = items.firstOrNull { it.id == id }
                    currentDownloadId = null
                    when (action) {
                        PendingAction.OPEN -> {
                            dismissCurrentDialog()
                            currentDownloadAction = PendingAction.OPEN
                            launchViewer(file, item)
                        }
                        PendingAction.SAVE -> {
                            dismissCurrentDialog()
                            currentDownloadAction = PendingAction.OPEN
                            saveToPhone(file, item)
                        }
                        PendingAction.ZIP_QUEUE -> {
                            // One file done in the bulk run — pop it,
                            // refresh % bar, and request the next or
                            // finalise the zip.
                            val finished = zipQueue.removeFirstOrNull()
                            if (finished != null && item != null) {
                                zipQueueFiles.add(item to file)
                            }
                            updateZipProgressDialog()
                            if (zipQueue.isEmpty()) finalizeZip()
                            else zipKickNext()
                        }
                    }
                }
            }
        }
        plugin.onDeleteResult = { id, ok ->
            runOnUiThread {
                if (pendingBulkDeletes.isNotEmpty()) {
                    pendingBulkDeletes.remove(id)
                    selectedIds.remove(id)
                    renderToolbarForMode()
                    if (pendingBulkDeletes.isEmpty()) {
                        listProgress.visibility = View.GONE
                        Toast.makeText(this, "Deleted batch", Toast.LENGTH_SHORT).show()
                        if (selectedIds.isEmpty()) exitSelectionMode()
                    } else {
                        toolbar.subtitle = "Deleting ${pendingBulkDeletes.size} more…"
                    }
                } else {
                    Toast.makeText(
                        this,
                        if (ok) "Deleted" else "Delete failed",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        plugin.onUploadReady = { wifiUrl ->
            // Wake the upload worker. We don't post to the UI thread
            // here — the worker is already off the main thread.
            synchronized(readyLatch) {
                readyResult = ReadyState(wifiUrl)
                (readyLatch as Object).notifyAll()
            }
        }
        plugin.onUploadAck = { name, ok, message ->
            synchronized(btAckLatch) {
                if (btAckPending == name) {
                    btAckOk = ok
                    btAckMessage = message
                    btAckPending = null
                    (btAckLatch as Object).notifyAll()
                }
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
        GalleryPlugin.instance?.onUploadReady = null
        GalleryPlugin.instance?.onUploadAck = null
    }

    private fun refresh() {
        val sent = GalleryPlugin.instance?.requestList() ?: false
        listProgress.visibility = if (sent) View.VISIBLE else View.GONE
        if (!sent) {
            Toast.makeText(this, "Glass not connected", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onListUpdated(list: List<GalleryItem>) {
        items = list
        // Drop any selected ids that no longer exist (e.g. deleted on glass).
        if (selectedIds.retainAll(items.map { it.id }.toSet())) {
            if (selectedIds.isEmpty() && inSelectionMode) exitSelectionMode()
        }
        adapter.notifyDataSetChanged()
        emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        listProgress.visibility = View.GONE
        renderToolbarForMode()
    }

    // --- Selection mode ---

    private fun enterSelectionMode() {
        if (inSelectionMode) return
        inSelectionMode = true
        renderToolbarForMode()
    }

    private fun exitSelectionMode() {
        if (!inSelectionMode) return
        inSelectionMode = false
        selectedIds.clear()
        adapter.notifyDataSetChanged()
        renderToolbarForMode()
    }

    private fun toggleSelected(item: GalleryItem) {
        if (selectedIds.contains(item.id)) selectedIds.remove(item.id)
        else selectedIds.add(item.id)
        if (selectedIds.isEmpty()) {
            exitSelectionMode()
        } else {
            adapter.notifyDataSetChanged()
            renderToolbarForMode()
        }
    }

    private fun selectAll() {
        selectedIds.clear()
        selectedIds.addAll(items.map { it.id })
        adapter.notifyDataSetChanged()
        renderToolbarForMode()
    }

    // --- Bulk delete ---

    private fun confirmBulkDelete() {
        val ids = selectedIds.toList()
        if (ids.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete ${ids.size} items?")
            .setMessage("This permanently removes the selected files from the glass.")
            .setPositiveButton("Delete") { _, _ -> runBulkDelete(ids) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runBulkDelete(ids: List<String>) {
        val plugin = GalleryPlugin.instance
        if (plugin == null) {
            Toast.makeText(this, "Glass not connected", Toast.LENGTH_SHORT).show()
            return
        }
        pendingBulkDeletes.clear()
        pendingBulkDeletes.addAll(ids)
        listProgress.visibility = View.VISIBLE
        toolbar.subtitle = "Deleting ${ids.size}…"
        // Fire all DELETE messages in order. Each one already has an
        // on-glass ack and the existing onDeleteResult handler removes
        // the id from items. We listen for those acks below to update
        // status + finalise the run.
        var sentCount = 0
        for (id in ids) {
            if (plugin.delete(id)) sentCount++
        }
        if (sentCount == 0) {
            Toast.makeText(this, "Glass not connected", Toast.LENGTH_SHORT).show()
            pendingBulkDeletes.clear()
        }
    }

    // --- Upload from phone ---

    private fun launchUploadPicker() {
        if (uploadThread != null) {
            Toast.makeText(this, "Upload already in progress", Toast.LENGTH_SHORT).show()
            return
        }
        if (GalleryPlugin.instance == null) {
            Toast.makeText(this, "GlassHole service not ready", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            // Temporarily widened to */* so APKs can ride the gallery
            // upload pipeline as a recovery channel when USB / BT APK
            // Manager / browser-download paths are broken. Revert to
            // ["image/*", "video/*"] once normal install routes work.
            pickMediaLauncher.launch(arrayOf("*/*"))
        } catch (e: Exception) {
            Toast.makeText(this, "No file picker available: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun startUpload(uris: List<Uri>) {
        val plugin = GalleryPlugin.instance ?: return
        uploadTotal = uris.size
        uploadCompleted = 0
        showZipProgressDialog("Uploading ${uris.size} items", "Negotiating transport…")
        // Negotiate the wifi vs BT path before kicking off the worker;
        // the worker observes the result via the shared latch.
        readyResult = null
        uploadThread = Thread {
            try {
                val ready = waitForUploadReady(plugin)
                if (ready == null) {
                    runOnUiThread {
                        dismissCurrentDialog()
                        Toast.makeText(this, "Glass didn't respond to UPLOAD_OFFER", Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }
                val transport = if (ready.wifiUrl != null) "Wi-Fi" else "Bluetooth"
                runOnUiThread {
                    currentDialogMessage?.text = "Transport: $transport"
                }
                for (uri in uris) {
                    val info = readUriInfo(uri) ?: continue
                    runOnUiThread {
                        currentDialogMessage?.text = "Uploading ${info.name} ($transport)…"
                        currentDialogProgress?.progress =
                            if (uploadTotal > 0) (uploadCompleted * 100 / uploadTotal) else 0
                        currentDialogDetail?.text = "${uploadCompleted + 1} of $uploadTotal"
                    }
                    val ok = if (ready.wifiUrl != null) {
                        uploadOverWifi(ready.wifiUrl, uri, info)
                    } else {
                        uploadOverBt(plugin, uri, info)
                    }
                    if (!ok) {
                        runOnUiThread {
                            Toast.makeText(this,
                                "Upload of ${info.name} failed", Toast.LENGTH_LONG).show()
                        }
                    }
                    uploadCompleted++
                    runOnUiThread {
                        currentDialogProgress?.progress =
                            if (uploadTotal > 0) (uploadCompleted * 100 / uploadTotal) else 0
                        currentDialogDetail?.text = "$uploadCompleted of $uploadTotal"
                    }
                }
                runOnUiThread {
                    dismissCurrentDialog()
                    Toast.makeText(this,
                        "Uploaded $uploadCompleted of $uploadTotal — refreshing",
                        Toast.LENGTH_SHORT).show()
                    refresh()
                }
            } finally {
                uploadThread = null
            }
        }.apply { isDaemon = true; name = "GalleryUpload"; start() }
    }

    /** Send UPLOAD_OFFER and block up to 6 seconds for UPLOAD_READY. */
    private fun waitForUploadReady(plugin: GalleryPlugin): ReadyState? {
        readyResult = null
        if (!plugin.requestUploadOffer()) return null
        synchronized(readyLatch) {
            val deadline = System.currentTimeMillis() + 6_000
            while (readyResult == null) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break
                try { (readyLatch as Object).wait(remaining) } catch (_: InterruptedException) { return null }
            }
        }
        return readyResult
    }

    private data class UploadInfo(
        val name: String,
        val size: Long,
        val mime: String,
        val type: String  // "image" / "video"
    )

    private fun readUriInfo(uri: Uri): UploadInfo? {
        return try {
            val mime = contentResolver.getType(uri).orEmpty()
            val type = when {
                mime.startsWith("video/") -> "video"
                mime.startsWith("image/") -> "image"
                else -> "image"
            }
            var name: String? = null
            var size = 0L
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (c.moveToFirst()) {
                    if (ni >= 0) name = c.getString(ni)
                    if (si >= 0) size = c.getLong(si)
                }
            }
            UploadInfo(
                name = name ?: "phone-upload-${System.currentTimeMillis()}",
                size = size,
                mime = mime,
                type = type
            )
        } catch (e: Exception) {
            Log.w("GalleryUpload", "readUriInfo failed: ${e.message}")
            null
        }
    }

    private fun uploadOverWifi(baseUrl: String, uri: Uri, info: UploadInfo): Boolean {
        return try {
            val url = baseUrl +
                "&name=" + java.net.URLEncoder.encode(info.name, "UTF-8") +
                "&type=" + info.type
            val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 5_000
                readTimeout = 30_000
                setFixedLengthStreamingMode(info.size)
                setRequestProperty("Content-Type", info.mime.ifEmpty { "application/octet-stream" })
                setRequestProperty("Connection", "close")
            }
            try {
                contentResolver.openInputStream(uri).use { input ->
                    if (input == null) return false
                    conn.outputStream.use { out ->
                        val buf = ByteArray(64 * 1024)
                        var sent = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            sent += n
                            // Report fine-grained progress for the
                            // current file as a fraction of the
                            // overall run.
                            if (info.size > 0) {
                                val pctOfFile = (sent.toDouble() / info.size).coerceIn(0.0, 1.0)
                                val overallPct =
                                    ((uploadCompleted + pctOfFile) * 100.0 / uploadTotal).toInt()
                                runOnUiThread {
                                    currentDialogProgress?.progress = overallPct
                                }
                            }
                        }
                    }
                }
                val code = conn.responseCode
                if (code != 200) {
                    Log.w("GalleryUpload", "wifi upload HTTP $code for ${info.name}")
                    return false
                }
                true
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w("GalleryUpload", "wifi upload failed: ${e.message}")
            false
        }
    }

    private fun uploadOverBt(plugin: GalleryPlugin, uri: Uri, info: UploadInfo): Boolean {
        return try {
            // Compute MD5 in one pass alongside reading bytes — avoids
            // a separate full-file read on the phone side.
            val md5 = java.security.MessageDigest.getInstance("MD5")
            val chunks = ArrayList<ByteArray>()
            contentResolver.openInputStream(uri)?.use { input ->
                val buf = ByteArray(48 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    val slice = if (n == buf.size) buf.copyOf() else buf.copyOf(n)
                    md5.update(slice)
                    chunks.add(slice)
                }
            } ?: return false
            val md5Hex = md5.digest().joinToString("") { "%02x".format(it) }

            btAckPending = info.name
            btAckOk = false
            btAckMessage = null

            if (!plugin.sendUploadStart(info.name, info.type, info.size, md5Hex)) return false
            for (slice in chunks) {
                val b64 = android.util.Base64.encodeToString(slice, android.util.Base64.NO_WRAP)
                if (!plugin.sendUploadDataChunk(b64)) return false
            }
            if (!plugin.sendUploadEnd(info.name)) return false

            // Wait up to 30s for UPLOAD_ACK.
            synchronized(btAckLatch) {
                val deadline = System.currentTimeMillis() + 30_000
                while (btAckPending != null) {
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) break
                    try { (btAckLatch as Object).wait(remaining) } catch (_: InterruptedException) { break }
                }
            }
            btAckOk
        } catch (e: Exception) {
            Log.w("GalleryUpload", "bt upload failed: ${e.message}")
            false
        }
    }

    // --- Bulk save-as-zip ---

    private fun startBulkSaveZip() {
        if (selectedIds.isEmpty()) return
        if (zipQueue.isNotEmpty()) {
            Toast.makeText(this, "A zip is already in progress", Toast.LENGTH_SHORT).show()
            return
        }
        val plugin = GalleryPlugin.instance ?: run {
            Toast.makeText(this, "Glass not connected", Toast.LENGTH_SHORT).show()
            return
        }
        val ordered = selectedIds.mapNotNull { id -> items.firstOrNull { it.id == id } }
        if (ordered.isEmpty()) return

        zipQueue.clear()
        zipQueueFiles.clear()
        zipQueue.addAll(ordered)

        val total = ordered.size
        showZipProgressDialog("Saving $total items as zip", "Preparing…")
        currentDownloadAction = PendingAction.ZIP_QUEUE
        zipKickNext()
    }

    /**
     * Inflate the M3 custom-view progress dialog. We track the inner
     * views ourselves so [updateZipProgressDialog] can update them
     * without re-creating the dialog.
     */
    private fun showZipProgressDialog(title: String, initialMessage: String) {
        currentDialog?.dismiss()
        val view = LayoutInflater.from(this)
            .inflate(R.layout.dialog_zip_progress, null)
        currentDialogMessage = view.findViewById(R.id.zipProgressMessage)
        currentDialogProgress = view.findViewById(R.id.zipProgressBar)
        currentDialogDetail = view.findViewById(R.id.zipProgressDetail)
        currentDialogMessage?.text = initialMessage
        currentDialogProgress?.isIndeterminate = false
        currentDialogProgress?.progress = 0
        currentDialogDetail?.text = ""
        currentDialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(view)
            .setCancelable(false)
            .show()
    }

    /**
     * Single-item indeterminate spinner for open / save downloads.
     */
    private fun showSingleDownloadDialog(title: String) {
        currentDialog?.dismiss()
        val view = LayoutInflater.from(this)
            .inflate(R.layout.dialog_zip_progress, null)
        currentDialogMessage = view.findViewById(R.id.zipProgressMessage)
        currentDialogProgress = view.findViewById(R.id.zipProgressBar)
        currentDialogDetail = view.findViewById(R.id.zipProgressDetail)
        currentDialogMessage?.text = "Downloading…"
        currentDialogProgress?.isIndeterminate = false
        currentDialogProgress?.progress = 0
        currentDialogDetail?.text = ""
        currentDialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(view)
            .setCancelable(true)
            .show()
    }

    private fun dismissCurrentDialog() {
        currentDialog?.dismiss()
        currentDialog = null
        currentDialogMessage = null
        currentDialogProgress = null
        currentDialogDetail = null
    }

    private fun zipKickNext() {
        val plugin = GalleryPlugin.instance ?: return
        val next = zipQueue.firstOrNull() ?: return
        currentDownloadId = next.id
        val cached = plugin.cachedFile(next)
        if (cached.exists() && cached.length() == next.size) {
            // Skip download — file's already here. Synthesise the
            // "complete" callback so the rest of the loop runs.
            zipQueueFiles.add(next to cached)
            zipQueue.removeFirstOrNull()
            updateZipProgressDialog()
            if (zipQueue.isEmpty()) finalizeZip()
            else zipKickNext()
            return
        }
        currentDialogMessage?.text = "Downloading ${next.name}…"
        if (!plugin.requestFull(next.id)) {
            zipFailAndCleanup("Glass not connected")
        }
    }

    private fun updateZipProgressDialog() {
        val totalCount = zipQueueFiles.size + zipQueue.size
        val done = zipQueueFiles.size
        val pct = if (totalCount > 0) (done * 100 / totalCount) else 0
        currentDialogProgress?.progress = pct
        currentDialogDetail?.text = "$done of $totalCount"
    }

    private fun finalizeZip() {
        currentDialogMessage?.text = "Writing zip…"
        Thread {
            val files = zipQueueFiles.toList()
            zipQueueFiles.clear()
            try {
                val name = "GlassHole-${zipFilenameTimestamp()}.zip"
                writeZipToDownloads(name, files)
                runOnUiThread {
                    dismissCurrentDialog()
                    currentDownloadAction = PendingAction.OPEN
                    currentDownloadId = null
                    Toast.makeText(
                        this,
                        "Saved $name to Downloads",
                        Toast.LENGTH_LONG
                    ).show()
                    exitSelectionMode()
                }
            } catch (e: Exception) {
                runOnUiThread { zipFailAndCleanup("Zip failed: ${e.message}") }
            }
        }.apply { isDaemon = true; name = "GalleryZip"; start() }
    }

    private fun zipFailAndCleanup(message: String) {
        dismissCurrentDialog()
        currentDownloadId = null
        currentDownloadAction = PendingAction.OPEN
        zipQueue.clear()
        zipQueueFiles.clear()
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun zipFilenameTimestamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault())
            .format(Date(System.currentTimeMillis()))

    private fun writeZipToDownloads(zipName: String, entries: List<Pair<GalleryItem, File>>) {
        // Names inside the zip — disambiguate if two glass files happen
        // to share a basename.
        val seenNames = HashMap<String, Int>()
        val resolveName: (GalleryItem) -> String = { it ->
            val base = it.name.ifBlank { "${it.id}.bin" }
            val n = (seenNames[base] ?: 0) + 1
            seenNames[base] = n
            if (n == 1) base else {
                val dot = base.lastIndexOf('.')
                if (dot > 0) "${base.substring(0, dot)}-$n${base.substring(dot)}"
                else "$base-$n"
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, zipName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/GlassHole")
            }
            val uri = contentResolver.insert(
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                values
            ) ?: throw java.io.IOException("Couldn't create MediaStore entry")
            contentResolver.openOutputStream(uri)?.use { out ->
                java.util.zip.ZipOutputStream(out.buffered()).use { zip ->
                    for ((item, file) in entries) {
                        val entryName = resolveName(item)
                        zip.putNextEntry(java.util.zip.ZipEntry(entryName))
                        FileInputStream(file).use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            } ?: throw java.io.IOException("Couldn't open output stream")
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            ).resolve("GlassHole")
            dir.mkdirs()
            val out = File(dir, zipName)
            java.util.zip.ZipOutputStream(out.outputStream().buffered()).use { zip ->
                for ((item, file) in entries) {
                    val entryName = resolveName(item)
                    zip.putNextEntry(java.util.zip.ZipEntry(entryName))
                    FileInputStream(file).use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
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
                // Single-item helper isn't called for the bulk path —
                // [zipKickNext] hits the cache directly.
                PendingAction.ZIP_QUEUE -> Unit
            }
            return
        }
        showSingleDownloadDialog(item.name)
        currentDownloadId = item.id
        currentDownloadAction = action
        if (!plugin.requestFull(item.id)) {
            dismissCurrentDialog()
            currentDownloadId = null
            Toast.makeText(this, "Glass not connected", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showItemMenu(item: GalleryItem) {
        val options = arrayOf("Open", "Save to phone", "Delete")
        MaterialAlertDialogBuilder(this)
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
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete ${item.name}?")
            .setMessage("This permanently removes the file from the glass.")
            .setPositiveButton("Delete") { _, _ ->
                val sent = GalleryPlugin.instance?.delete(item.id) ?: false
                if (!sent) Toast.makeText(this, "Glass not connected", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // In-memory cache + dedup set so the same thumb URL isn't fetched
    // multiple times as the GridView recycles views during scroll.
    private val thumbCache = Collections.synchronizedMap(LinkedHashMap<String, android.graphics.Bitmap>())
    private val inflightThumbs = Collections.synchronizedSet(mutableSetOf<String>())
    /** WiFi thumb fetches that have failed once — we don't retry, just
     *  fall back to the embedded base64 thumb. Avoids a thundering
     *  herd of doomed requests when the phone is on a different
     *  network than the glass. */
    private val failedThumbUrls = Collections.synchronizedSet(mutableSetOf<String>())
    private val thumbExecutor = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "GalleryThumbFetch").apply { isDaemon = true }
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
            val card = view.findViewById<com.google.android.material.card.MaterialCardView>(R.id.thumbCard)
            val selectedTint = view.findViewById<View>(R.id.thumbSelectedTint)
            val selectedCheck = view.findViewById<ImageView>(R.id.thumbSelectedCheck)

            val selected = inSelectionMode && item.id in selectedIds
            selectedTint.visibility = if (selected) View.VISIBLE else View.GONE
            selectedCheck.visibility = if (selected) View.VISIBLE else View.GONE
            card.strokeWidth = if (selected)
                (2 * resources.displayMetrics.density).toInt() else 0

            // Tag tracks the row's current item so a slow HTTP fetch
            // landing after the row was recycled doesn't paint the
            // wrong image.
            image.tag = item.id

            val cached = thumbCache[item.id]
            if (cached != null) {
                image.setImageBitmap(cached)
            } else {
                // Set base64 fallback FIRST so the cell never flashes
                // empty. The HTTP fetch (if any) will overwrite when it
                // lands.
                val fallback = decodeThumb(item.thumbBase64)
                if (fallback != null) image.setImageBitmap(fallback)
                else image.setImageResource(android.R.drawable.ic_menu_gallery)

                val url = item.thumbUrl
                if (url != null && url !in failedThumbUrls && url !in inflightThumbs) {
                    inflightThumbs.add(url)
                    thumbExecutor.submit { fetchThumbAsync(item, url) }
                }
            }

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

        private fun fetchThumbAsync(item: GalleryItem, url: String) {
            try {
                val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 3000
                    readTimeout = 8000
                }
                try {
                    conn.connect()
                    if (conn.responseCode != 200) throw java.io.IOException("HTTP ${conn.responseCode}")
                    val bytes = conn.inputStream.use { it.readBytes() }
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
                    thumbCache[item.id] = bmp
                    runOnUiThread {
                        // Repaint any visible cell still bound to this id.
                        adapter.notifyDataSetChanged()
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (_: Exception) {
                failedThumbUrls.add(url)
                // No need to invalidate — the base64 fallback is already
                // showing.
            } finally {
                inflightThumbs.remove(url)
            }
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
