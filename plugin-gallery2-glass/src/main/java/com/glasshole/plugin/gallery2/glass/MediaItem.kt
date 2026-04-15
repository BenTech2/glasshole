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

    /**
     * Scan the standard glass media directories and return all images and
     * videos sorted newest-first. Mirrors the paths the Google GallerySample
     * uses plus the DCIM/Pictures/Movies conventions EE2 follows.
     */
    fun scan(): List<MediaItem> {
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
                    val path = f.absolutePath
                    if (seen.add(path)) {
                        out.add(MediaItem(f, isVideo, f.lastModified()))
                    }
                }
        }
        return out.sortedByDescending { it.dateModified }
    }

    fun loadThumbnail(context: Context, item: MediaItem, target: Int = 360): Bitmap? {
        return try {
            if (item.isVideo) {
                ThumbnailUtils.createVideoThumbnail(
                    item.file.absolutePath,
                    MediaStore.Images.Thumbnails.MINI_KIND
                )
            } else {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(item.file.absolutePath, opts)
                val scale = maxOf(1, minOf(opts.outWidth, opts.outHeight) / target)
                val loadOpts = BitmapFactory.Options().apply { inSampleSize = scale }
                BitmapFactory.decodeFile(item.file.absolutePath, loadOpts)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Thumbnail failed for ${item.file.name}: ${e.message}")
            null
        }
    }
}
