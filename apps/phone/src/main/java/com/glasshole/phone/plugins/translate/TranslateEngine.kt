// SPDX-License-Identifier: MIT
package com.glasshole.phone.plugins.translate

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Wraps ML Kit on-device Text Recognition + Translation into a single
 * blocking pipeline. Designed to run inside the BridgeService thread
 * pool — each [translate] call is synchronous + returns when both
 * stages finish.
 *
 * All models are downloaded on first use. After download they live
 * in the app's ML Kit cache (~25 MB OCR per script, ~30 MB per
 * translation language pair). No network round-trips at translation
 * time.
 *
 * Each [TranslateEngine] instance is bound to a specific source
 * language; switch via [forSource]. The translator (target side)
 * is configurable per-call.
 */
class TranslateEngine private constructor(
    private val sourceLang: String,
    private val recognizer: TextRecognizer
) {

    companion object {
        private const val TAG = "TranslateEngine"

        /** ML Kit source language → recognizer factory + ISO code for
         *  the translator. Add an entry here to expose another source
         *  script via the settings UI. */
        fun forSource(sourceLang: String): TranslateEngine {
            val recognizer = when (sourceLang) {
                TranslateLanguage.JAPANESE -> TextRecognition.getClient(
                    JapaneseTextRecognizerOptions.Builder().build()
                )
                TranslateLanguage.CHINESE -> TextRecognition.getClient(
                    ChineseTextRecognizerOptions.Builder().build()
                )
                TranslateLanguage.KOREAN -> TextRecognition.getClient(
                    KoreanTextRecognizerOptions.Builder().build()
                )
                else -> TextRecognition.getClient(
                    TextRecognizerOptions.DEFAULT_OPTIONS
                )
            }
            return TranslateEngine(sourceLang, recognizer)
        }
    }

    /** One translated text block on the source image. The bbox is
     *  in pixel coords of the supplied JPEG; the consumer overlays
     *  scale to its render dimensions. */
    data class Block(
        val original: String,
        val translated: String,
        val bbox: Rect,
    )

    /** Decode the JPEG, run OCR, then translate each detected block
     *  via an on-device JP→EN (or other) model. Returns a list of
     *  blocks ordered by bbox.top (reading order, top-to-bottom).
     *
     *  Throws on JPEG decode failure or ML Kit task error so the
     *  caller can report a clean error back to glass. */
    fun translate(jpeg: ByteArray, targetLang: String): List<Block> {
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            ?: error("JPEG decode failed (${jpeg.size} bytes)")
        try {
            return runPipeline(bitmap, targetLang)
        } finally {
            bitmap.recycle()
        }
    }

    private fun runPipeline(bitmap: Bitmap, targetLang: String): List<Block> {
        val input = InputImage.fromBitmap(bitmap, 0)
        Log.i(TAG, "OCR start (${bitmap.width}x${bitmap.height}, src=$sourceLang)")
        val ocrStart = System.currentTimeMillis()
        val ocrResult = Tasks.await(recognizer.process(input))
        val ocrMs = System.currentTimeMillis() - ocrStart
        val blocks = ocrResult.textBlocks
        Log.i(TAG, "OCR done in ${ocrMs}ms: ${blocks.size} block(s)")

        if (blocks.isEmpty()) return emptyList()

        val translator: Translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(sourceLang)
                .setTargetLanguage(targetLang)
                .build()
        )
        try {
            // Make sure the translation model is downloaded. First
            // call blocks until the ~30 MB model lands; subsequent
            // calls hit local cache. Wi-Fi-only by default — same
            // policy ML Kit uses everywhere.
            Tasks.await(translator.downloadModelIfNeeded(
                DownloadConditions.Builder().requireWifi().build()
            ))

            val txStart = System.currentTimeMillis()
            val out = mutableListOf<Block>()
            for (b in blocks) {
                val original = b.text
                if (original.isBlank()) continue
                val rect = b.boundingBox ?: continue
                val translated = try {
                    Tasks.await(translator.translate(original))
                } catch (e: Throwable) {
                    Log.w(TAG, "translate failed for \"$original\": ${e.message}")
                    original  // fall back to source
                }
                out.add(Block(original = original, translated = translated, bbox = rect))
            }
            val txMs = System.currentTimeMillis() - txStart
            Log.i(TAG, "Translation done in ${txMs}ms: ${out.size} block(s)")

            // Reading order — top-to-bottom by bbox center; ties by left edge.
            return out.sortedWith(
                compareBy({ it.bbox.top + it.bbox.height() / 2 }, { it.bbox.left })
            )
        } finally {
            translator.close()
        }
    }

    /** Free the recognizer — caller should call once when the plugin
     *  shuts down or when switching source languages. */
    fun close() {
        try { recognizer.close() } catch (_: Throwable) {}
    }
}
