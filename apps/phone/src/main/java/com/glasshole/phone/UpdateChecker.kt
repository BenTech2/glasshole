package com.glasshole.phone

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Hits the public GitHub releases API to see if a newer GlassHole
 * has shipped than the one the user is running. Async; caller passes
 * a callback that fires on the main thread once the check completes
 * (or fails — failures are silent, the banner just stays hidden).
 *
 * Throttled to one check per CHECK_THROTTLE_MS to keep the API
 * unrate-limited; per-tag dismiss state lets the user silence one
 * specific release without losing alerts for the *next* release.
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val PREFS = "update_checker"
    private const val KEY_LAST_CHECK_MS = "last_check_ms"
    private const val KEY_DISMISSED_TAG = "dismissed_tag"
    private const val KEY_CACHED_TAG = "cached_tag"
    private const val KEY_CACHED_URL = "cached_url"

    private const val CHECK_THROTTLE_MS = 6L * 60 * 60 * 1_000L // 6 hours
    private const val RELEASES_URL =
        "https://api.github.com/repos/BenTech2/glasshole/releases/latest"

    data class AvailableUpdate(val tag: String, val url: String)

    /** Run a network check on a daemon thread. The callback fires on
     *  the main thread with the latest update if one is available and
     *  hasn't been dismissed for the same tag, otherwise null. */
    fun checkInBackground(
        context: Context,
        currentVersion: String,
        forceRefresh: Boolean = false,
        onResult: (AvailableUpdate?) -> Unit
    ) {
        val ctx = context.applicationContext
        val main = Handler(Looper.getMainLooper())

        // Replay cached result immediately if it's still fresh — keeps
        // the banner on screen across activity restarts without a
        // second network round-trip.
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastCheck = prefs.getLong(KEY_LAST_CHECK_MS, 0L)
        val cachedTag = prefs.getString(KEY_CACHED_TAG, null)
        val cachedUrl = prefs.getString(KEY_CACHED_URL, null)
        val cacheFresh = (System.currentTimeMillis() - lastCheck) < CHECK_THROTTLE_MS
        if (cacheFresh && cachedTag != null && cachedUrl != null && !forceRefresh) {
            main.post { onResult(buildResult(prefs, currentVersion, cachedTag, cachedUrl)) }
            return
        }

        Thread({
            val fetched = try { fetchLatest() } catch (e: Exception) {
                Log.w(TAG, "GitHub releases fetch failed: ${e.message}")
                null
            }
            if (fetched != null) {
                prefs.edit()
                    .putLong(KEY_LAST_CHECK_MS, System.currentTimeMillis())
                    .putString(KEY_CACHED_TAG, fetched.first)
                    .putString(KEY_CACHED_URL, fetched.second)
                    .apply()
            }
            val result = if (fetched != null) {
                buildResult(prefs, currentVersion, fetched.first, fetched.second)
            } else {
                // Network failed — fall back to the (possibly stale) cache.
                if (cachedTag != null && cachedUrl != null)
                    buildResult(prefs, currentVersion, cachedTag, cachedUrl)
                else null
            }
            main.post { onResult(result) }
        }, "GlassHoleUpdateCheck").apply { isDaemon = true; start() }
    }

    /** Mark a specific remote tag as dismissed. The banner stays
     *  hidden for that exact tag forever; future releases blow past
     *  the gate because the dismissed tag no longer matches. */
    fun dismiss(context: Context, tag: String) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DISMISSED_TAG, tag)
            .apply()
    }

    private fun buildResult(
        prefs: android.content.SharedPreferences,
        currentVersion: String,
        remoteTag: String,
        remoteUrl: String
    ): AvailableUpdate? {
        val dismissed = prefs.getString(KEY_DISMISSED_TAG, null)
        if (dismissed != null && dismissed == remoteTag) return null
        if (!isNewer(remoteTag, currentVersion)) return null
        return AvailableUpdate(remoteTag, remoteUrl)
    }

    private fun fetchLatest(): Pair<String, String>? {
        val conn = (URL(RELEASES_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "GlassHole/UpdateChecker")
        }
        try {
            if (conn.responseCode !in 200..299) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val obj = JSONObject(body)
            val tag = obj.optString("tag_name").takeIf { it.isNotEmpty() } ?: return null
            val url = obj.optString("html_url").ifEmpty {
                "https://github.com/BenTech2/glasshole/releases/tag/$tag"
            }
            return Pair(tag, url)
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    /** Lexicographic-by-component numeric compare with prerelease
     *  awareness. "1.0.1" > "1.0.0", "1.0.1" > "1.0.1-alpha",
     *  "1.0.1-alpha" == "1.0.1-alpha". Strips a leading "v" on the
     *  remote tag so "v1.0.1" lines up against BuildConfig's
     *  un-prefixed version string. */
    internal fun isNewer(remote: String, current: String): Boolean {
        val r = parseVersion(remote)
        val c = parseVersion(current)
        val len = maxOf(r.numbers.size, c.numbers.size)
        for (i in 0 until len) {
            val rn = r.numbers.getOrElse(i) { 0 }
            val cn = c.numbers.getOrElse(i) { 0 }
            if (rn > cn) return true
            if (rn < cn) return false
        }
        // Numeric tail equal — final-release beats prerelease of the
        // same number; otherwise treat them as equal (no banner).
        return r.prerelease.isEmpty() && c.prerelease.isNotEmpty()
    }

    private data class ParsedVersion(val numbers: List<Int>, val prerelease: String)

    private fun parseVersion(v: String): ParsedVersion {
        val stripped = v.removePrefix("v").removePrefix("V")
        val plus = stripped.substringBefore("+")
        val base = plus.substringBefore("-")
        val pre = plus.substringAfter("-", "")
        val numbers = base.split(".").map { it.toIntOrNull() ?: 0 }
        return ParsedVersion(numbers, pre)
    }
}
