package com.glasshole.glassxe.home

import android.view.View
import android.widget.TextView
import androidx.viewpager2.widget.ViewPager2
import com.glasshole.glassxe.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Apple Cover Flow (iTunes / iPod / OS X Finder) page transformer.
 *
 * Key visual traits we replicate:
 *   - Center page is flat, facing the viewer
 *   - Every neighbor snaps to the same steep Y-tilt (about 70°), so side
 *     pages fold back like a deck of cards rather than fanning out
 *   - Each page pivots around its INNER edge (the edge closest to
 *     center) so the tilt recedes into the background
 *   - Very tight horizontal overlap — each side page shows only a
 *     sliver of its face through the tilts of pages in front
 *   - Subtle scale + alpha falloff with distance for perspective
 *   - Center page renders on top of the stack via translationZ
 */
class CoverFlowTransformer : ViewPager2.PageTransformer {

    override fun transformPage(page: View, position: Float) {
        val pw = page.width.toFloat()
        val absPos = abs(position)
        val absClamped = min(absPos, MAX_VISIBLE_RANGE)

        page.cameraDistance = pw * 6f

        val pivotRamp = position.coerceIn(-1f, 1f)
        page.pivotX = pw * (0.5f - 0.5f * pivotRamp)
        page.pivotY = page.height * 0.5f

        val tiltRamp = position.coerceIn(-1f, 1f)
        page.rotationY = -tiltRamp * MAX_TILT_DEG

        val scale = if (absPos <= 1f) {
            1f + (CENTER_EMPHASIS - 1f) * (1f - absPos)
        } else {
            max(MIN_SCALE, 1f - SCALE_FALLOFF * absClamped)
        }
        page.scaleX = scale
        page.scaleY = scale

        val extraBeyondFirst = (absPos - 1f).coerceAtLeast(0f)
        val pullSign = if (position >= 0f) -1f else 1f
        page.translationX = pullSign * extraBeyondFirst * pw * PULL_FACTOR

        page.alpha = max(MIN_ALPHA, 1f - ALPHA_FALLOFF * absClamped)

        // translationZ is API 21+; on EE1's API 19 we skip it — tilt + scale
        // alone still read as foreground, just without 3D Z-layering. Side
        // effect: tilted neighbors may occasionally poke above the center
        // page on KitKat, but the visual is close enough and there is no
        // runtime fallback for this property.
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            page.translationZ = -absClamped - 1f
        }

        page.findViewById<TextView>(R.id.appLabel)?.alpha =
            max(0f, 1f - LABEL_FADE * absPos)
    }

    companion object {
        private const val MAX_VISIBLE_RANGE = 4f
        private const val MAX_TILT_DEG = 70f
        private const val PULL_FACTOR = 0.65f
        private const val MIN_SCALE = 0.78f
        private const val SCALE_FALLOFF = 0.06f
        private const val MIN_ALPHA = 0.35f
        private const val ALPHA_FALLOFF = 0.22f
        /** How much bigger than natural size the center page renders. 1.2 =
         *  20% larger, tapering back to 1.0 at the ±1 neighbor slot. */
        private const val CENTER_EMPHASIS = 1.22f
        /** How fast the app-name label fades with distance from center. */
        private const val LABEL_FADE = 2.5f
    }
}
