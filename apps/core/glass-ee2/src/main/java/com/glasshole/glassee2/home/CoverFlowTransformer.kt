package com.glasshole.glassee2.home

import android.view.View
import android.widget.TextView
import androidx.viewpager2.widget.ViewPager2
import com.glasshole.glassee2.R
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

        // Strong perspective; scale with the page width so it looks right
        // regardless of density.
        page.cameraDistance = pw * 6f

        // Pivot migrates smoothly from center (at pos=0) to inner edge
        // (at pos=±1) and stays there beyond. The pivot is shared between
        // rotation and scale, so anchoring at center when the item is
        // focused keeps the "center emphasis" scale from drifting the
        // icon off-axis, while letting side items still fold around
        // their inner edge for the cover-flow perspective.
        val pivotRamp = position.coerceIn(-1f, 1f)
        page.pivotX = pw * (0.5f - 0.5f * pivotRamp)
        page.pivotY = page.height * 0.5f

        // Tilt ramps up within the first unit of distance, then clamps.
        // Neighbors 2, 3, 4... all wear the same tilt and just stack
        // behind each other — that's the deck-of-cards feel.
        //
        // Sign: rotationY is NEGATED relative to position. A right-of-center
        // page (position > 0) with pivot on its left edge gets rotationY < 0,
        // which swings the outer (right) edge into the depth while keeping
        // the inner edge facing the viewer. Positive rotationY would do the
        // opposite — the item would tilt toward the outside of the screen.
        val tiltRamp = position.coerceIn(-1f, 1f)
        page.rotationY = -tiltRamp * MAX_TILT_DEG

        // Center page pops slightly larger than its natural size so the
        // focused icon reads as "selected". The emphasis tapers linearly
        // to 1.0 at ±1 (edge of the neighbor slot), then the usual
        // falloff + floor kick in beyond that.
        val scale = if (absPos <= 1f) {
            1f + (CENTER_EMPHASIS - 1f) * (1f - absPos)
        } else {
            max(MIN_SCALE, 1f - SCALE_FALLOFF * absClamped)
        }
        page.scaleX = scale
        page.scaleY = scale

        // Pull: the immediate neighbors (±1) sit flush against the center
        // page — they should never slide behind it. Pages further out
        // (|pos| > 1) get increasingly pulled inward so the stack packs
        // tight like a real cover flow. Piecewise so the first step stays
        // flush while the rest keep compressing.
        val extraBeyondFirst = (absPos - 1f).coerceAtLeast(0f)
        val pullSign = if (position >= 0f) -1f else 1f
        page.translationX = pullSign * extraBeyondFirst * pw * PULL_FACTOR

        page.alpha = max(MIN_ALPHA, 1f - ALPHA_FALLOFF * absClamped)
        // Explicit gap so the center page always renders cleanly in front
        // of any tilted neighbor — otherwise overlapping side pages can
        // poke in front due to Z-fighting.
        page.translationZ = -absClamped - 1f

        // App labels of tilted neighbors would pile up under the center
        // item's label. Fade them out quickly so only the focused page's
        // label is visible.
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
