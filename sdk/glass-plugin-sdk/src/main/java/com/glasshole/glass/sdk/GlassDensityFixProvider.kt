package com.glasshole.glass.sdk

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * Stub ContentProvider whose only job is to call
 * [GlassDensityFix.applyIfNeeded] from its [onCreate]. ContentProvider
 * lifecycle hooks fire BEFORE `Application.onCreate` and well before
 * any Activity is instantiated, so the density patch lands before any
 * `setContentView()` inflates a layout.
 *
 * Declared in `glass-plugin-sdk`'s AndroidManifest so manifest-merging
 * propagates it into every plugin that depends on the SDK — no
 * per-plugin Application subclass required. The authority uses the
 * `${applicationId}` placeholder so each plugin gets a unique
 * authority and there's no install-time clash.
 *
 * No actual data is served — every query/insert/update/delete returns
 * null/0.
 */
class GlassDensityFixProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        context?.let { GlassDensityFix.applyIfNeeded(it) }
        return true
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri, values: ContentValues?,
        selection: String?, selectionArgs: Array<out String>?,
    ): Int = 0
}
