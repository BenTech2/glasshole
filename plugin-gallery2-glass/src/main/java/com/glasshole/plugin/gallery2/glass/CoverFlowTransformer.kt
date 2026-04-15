package com.glasshole.plugin.gallery2.glass

import android.view.View
import androidx.viewpager2.widget.ViewPager2

/**
 * Reproduces the iPod / macOS "Cover Flow" page look on top of ViewPager2.
 *
 * Each visible page is passed through this transformer with a `position`
 * value that's 0.0 at the center of the viewport, ±1.0 at ±one full page,
 * and so on. We use that offset to scale, rotate around the Y axis, and
 * translate each page horizontally so sibling pages overlap behind the
 * centered one.
 */
class CoverFlowTransformer(
    private val minScale: Float = 0.55f,
    private val rotationDeg: Float = 45f,
    private val overlapPercent: Float = 0.30f
) : ViewPager2.PageTransformer {

    override fun transformPage(page: View, position: Float) {
        val clamped = position.coerceIn(-2f, 2f)

        // Pages behind the centered one get a Y rotation that fakes perspective.
        page.cameraDistance = 12000f
        page.pivotX = if (clamped < 0) page.width.toFloat() else 0f
        page.pivotY = page.height * 0.5f
        page.rotationY = -rotationDeg * clamped.coerceIn(-1f, 1f)

        // Scale down as we move away from the center.
        val distance = Math.abs(clamped).coerceAtMost(1.5f)
        val scale = 1f - (1f - minScale) * distance
        page.scaleX = scale
        page.scaleY = scale

        // Horizontal overlap — pull off-center pages back toward the middle
        // so multiple cards are visible in a classic carousel.
        page.translationX = -page.width * overlapPercent * clamped

        // Alpha falloff for distant pages
        page.alpha = (1f - 0.4f * distance).coerceIn(0.3f, 1f)

        // Z-order: centered page on top
        page.translationZ = -Math.abs(clamped)
    }
}
