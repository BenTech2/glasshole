package com.glasshole.plugin.devtools.glass

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * Tiny SSH-triggerable trampoline that fires the standard
 * PackageInstaller UI for an APK at a given path. Lets the user
 * install APKs from inside the SSH shell — `pm install` itself isn't
 * available to a non-shell UID, but `am start -n` to PackageInstaller
 * is.
 *
 * Invocation:
 *
 *   # SCP the APK to a location the plugin can read without storage
 *   # permissions (its own external-cache works perfectly):
 *   scp foo.apk glass@<ip>:/sdcard/Android/data/com.glasshole.plugin.devtools.glass/cache/foo.apk
 *
 *   # Then over SSH (any value for `path` is fine; we accept absolute
 *   # paths anywhere under a FileProvider root):
 *   am start -n com.glasshole.plugin.devtools.glass/.InstallApkActivity \
 *       --es path /sdcard/Android/data/com.glasshole.plugin.devtools.glass/cache/foo.apk
 *
 *   # Or omit `path` and we install the only .apk in our external cache:
 *   am start -n com.glasshole.plugin.devtools.glass/.InstallApkActivity
 *
 * Glass shows the system "Install?" prompt; the user taps it once on
 * the headset.
 */
class InstallApkActivity : Activity() {

    companion object {
        private const val TAG = "DevToolsInstallApk"
        private const val AUTHORITY = "com.glasshole.plugin.devtools.glass.fileprovider"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val explicit = intent?.getStringExtra("path")
        val file: File = when {
            !explicit.isNullOrEmpty() -> File(explicit)
            else -> findSoloApkInCache() ?: run {
                bail("No path= extra and no APK in $externalCacheDir")
                return
            }
        }
        if (!file.exists()) {
            bail("Not found: ${file.absolutePath}")
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, AUTHORITY, file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            Log.i(TAG, "Launched installer for $uri (file=${file.absolutePath})")
        } catch (e: IllegalArgumentException) {
            // FileProvider throws this when the path is outside every
            // <paths> root. Tell the user to drop the file into a
            // covered directory.
            bail("Path outside FileProvider roots: ${file.absolutePath}\n" +
                "Copy it to ${externalCacheDir?.absolutePath ?: "<external cache>"} first")
        } catch (e: Exception) {
            Log.e(TAG, "startActivity failed", e)
            bail("startActivity: ${e.javaClass.simpleName}: ${e.message}")
        }
        finish()
    }

    private fun findSoloApkInCache(): File? {
        val dir = externalCacheDir ?: return null
        val apks = dir.listFiles { f -> f.isFile && f.name.lowercase().endsWith(".apk") }
            ?: return null
        return if (apks.size == 1) apks[0] else null
    }

    private fun bail(msg: String) {
        Log.w(TAG, msg)
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        finish()
    }
}
