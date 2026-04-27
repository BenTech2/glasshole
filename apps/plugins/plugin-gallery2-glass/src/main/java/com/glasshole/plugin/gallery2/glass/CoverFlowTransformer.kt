package com.glasshole.plugin.gallery2.glass

import android.view.View
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Apple Cover Flow page transformer — same geometry as the Home app
 * drawer. Center page is flat and scaled up; neighbors tilt steeply
 * around their inner edge so the outer edge recedes into the depth,
 * with tight overlap so several items fan out on screen at once.
 */
class CoverFlowTransformer : ViewPager2.PageTransformer {

    override fun transformPage(page: View, position: Float) {
        val pw = page.width.toFloat()
        val absPos = abs(position)
        val absClamped = min(absPos, MAX_VISIBLE_RANGE)

        page.cameraDistance = pw * 6f

        // Pivot migrates from center (pos=0) to inner edge (|pos|>=1) so
        // the center emphasis scales evenly while side pages still fold
        // around their inner edge.
        val pivotRamp = position.coerceIn(-1f, 1f)
        page.pivotX = pw * (0.5f - 0.5f * pivotRamp)
        page.pivotY = page.height * 0.5f

        // Tilt ramps up within the first unit of distance, then clamps —
        // neighbors 2, 3, 4... share the same tilt and just stack behind.
        val tiltRamp = position.coerceIn(-1f, 1f)
        page.rotationY = -tiltRamp * MAX_TILT_DEG

        // Center emphasis: focused page pops 22% larger, tapering to 1.0×
        // at the ±1 neighbor. Beyond that, the usual falloff kicks in.
        val scale = if (absPos <= 1f) {
            1f + (CENTER_EMPHASIS - 1f) * (1f - absPos)
        } else {
            max(MIN_SCALE, 1f - SCALE_FALLOFF * absClamped)
        }
        page.scaleX = scale
        page.scaleY = scale

        // Pull: the ±1 neighbors sit flush against the center, never
        // behind it. Pages at |pos|>1 get pulled inward to pack tight.
        val extraBeyondFirst = (absPos - 1f).coerceAtLeast(0f)
        val pullSign = if (position >= 0f) -1f else 1f
        page.translationX = pullSign * extraBeyondFirst * pw * PULL_FACTOR

        page.alpha = max(MIN_ALPHA, 1f - ALPHA_FALLOFF * absClamped)
        // translationZ is API 21+; on KitKat (XE / EE1) the deck-of-cards
        // layering is approximated via tilt + scale alone. Skip the line
        // entirely instead of falling back, so the View's z-axis stays at
        // its default and old hardware doesn't NoSuchMethodError.
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            page.translationZ = -absClamped - 1f
        }
    }

    companion object {
        private const val MAX_VISIBLE_RANGE = 4f
        private const val MAX_TILT_DEG = 70f
        private const val PULL_FACTOR = 0.65f
        private const val MIN_SCALE = 0.78f
        private const val SCALE_FALLOFF = 0.06f
        private const val MIN_ALPHA = 0.35f
        private const val ALPHA_FALLOFF = 0.22f
        private const val CENTER_EMPHASIS = 1.22f
    }
}
