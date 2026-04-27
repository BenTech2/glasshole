package com.glasshole.plugin.gallery2.glass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

data class MediaItem(
    val file: File,
    val isVideo: Boolean,
    val dateModified: Long
)

object MediaScanner {

    private const val TAG = "Gallery2Scan"

    private fun settingsPrefs(context: Context) =
        context.getSharedPreferences(Gallery2PluginService.PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Scan the standard glass media directories and return all images and
     * videos. Sort order (newest/oldest first) is read from plugin settings.
     */
    fun scan(context: Context): List<MediaItem> {
        val dirs = listOf(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera"),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        )
        val imgExt = setOf("jpg", "jpeg", "png", "webp")
        val vidExt = setOf("mp4", "mkv", "webm", "3gp")
        val seen = HashSet<String>()
        val out = mutableListOf<MediaItem>()

        // Skip tiny files — most of them are app caches / social-media
        // thumbnails that also happen to live in Pictures/DCIM, not
        // anything the user wants in their gallery.
        val minImageBytes = 80_000L
        val minVideoBytes = 200_000L

        for (dir in dirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            dir.walkTopDown()
                .maxDepth(3)
                .filter { it.isFile }
                .forEach { f ->
                    val ext = f.extension.lowercase()
                    val isImage = ext in imgExt
                    val isVideo = ext in vidExt
                    if (!isImage && !isVideo) return@forEach
                    val minBytes = if (isVideo) minVideoBytes else minImageBytes
                    if (f.length() < minBytes) return@forEach
                    val path = f.absolutePath
                    if (seen.add(path)) {
                        out.add(MediaItem(f, isVideo, f.lastModified()))
                    }
                }
        }
        val order = settingsPrefs(context).getString("sort_order", "newest") ?: "newest"
        return if (order == "oldest") {
            out.sortedBy { it.dateModified }
        } else {
            out.sortedByDescending { it.dateModified }
        }
    }

    fun loadThumbnail(context: Context, item: MediaItem, target: Int? = null): Bitmap? {
        val resolvedTarget = target ?: settingsPrefs(context).getInt("thumbnail_size_px", 360)
        return try {
            if (item.isVideo) {
                ThumbnailUtils.createVideoThumbnail(
                    item.file.absolutePath,
                    MediaStore.Images.Thumbnails.MINI_KIND
                )
            } else {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(item.file.absolutePath, opts)
                val scale = maxOf(1, minOf(opts.outWidth, opts.outHeight) / resolvedTarget)
                val loadOpts = BitmapFactory.Options().apply { inSampleSize = scale }
                BitmapFactory.decodeFile(item.file.absolutePath, loadOpts)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Thumbnail failed for ${item.file.name}: ${e.message}")
            null
        }
    }
}
