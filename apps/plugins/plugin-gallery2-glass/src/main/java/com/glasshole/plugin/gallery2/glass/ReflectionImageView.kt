package com.glasshole.plugin.gallery2.glass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView

/**
 * ImageView that draws an Apple Cover Flow-style reflection below its
 * normal content — a vertically-mirrored copy of the thumbnail (and
 * its rounded-rect background) faded out by a top-to-bottom alpha
 * gradient.
 *
 * The reflection renders *outside* the view's measured bounds, so the
 * direct parent must have clipChildren=false for it to be visible,
 * and the activity must reserve vertical space below the cards.
 *
 * Direct port of the same-named class in the EE1/EE2/XE base apps —
 * duplicated here because plugin-gallery2-glass is a separate APK
 * with its own package. If we end up wanting this in more plugins,
 * promote it to glass-plugin-sdk.
 *
 * Implementation: the view's normal drawing is captured into one
 * offscreen Bitmap, and the masked reflection is composed into a
 * second offscreen Bitmap using saveLayer + PorterDuff.Mode.DST_IN.
 * Both Bitmaps are then blitted to the view's canvas via plain
 * drawBitmap — the view's hw-accelerated canvas never sees the
 * saveLayer/PorterDuff pair, which on KitKat leaves dirty
 * framebuffer pixels past the view's bounds (visible as artifacts
 * around overlay views like the gallery's title chip).
 */
class ReflectionImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val reflectionFrac = 0.45f
    private val gapPx = (resources.displayMetrics.density * 4f).toInt()
    private val topAlpha = 0.40f

    private var iconCache: Bitmap? = null
    private var reflectionCache: Bitmap? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        iconCache?.recycle()
        reflectionCache?.recycle()
        if (w > 0 && h > 0) {
            iconCache = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val reflH = (h * reflectionFrac).toInt()
            reflectionCache = if (reflH > 0) {
                Bitmap.createBitmap(w, reflH, Bitmap.Config.ARGB_8888)
            } else null
        } else {
            iconCache = null
            reflectionCache = null
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        iconCache?.recycle()
        reflectionCache?.recycle()
        iconCache = null
        reflectionCache = null
    }

    override fun draw(canvas: Canvas) {
        val ic = iconCache
        if (ic == null || ic.isRecycled || drawable == null) {
            super.draw(canvas)
            return
        }

        ic.eraseColor(Color.TRANSPARENT)
        super.draw(Canvas(ic))

        val rc = reflectionCache
        if (rc != null && !rc.isRecycled) {
            rc.eraseColor(Color.TRANSPARENT)
            val rcCanvas = Canvas(rc)
            val h = ic.height.toFloat()
            val reflH = rc.height.toFloat()
            val w = rc.width.toFloat()

            val save = rcCanvas.saveLayer(0f, 0f, w, reflH, null, Canvas.ALL_SAVE_FLAG)

            val matrix = Matrix().apply {
                preScale(1f, -1f)
                postTranslate(0f, h)
            }
            rcCanvas.drawBitmap(ic, matrix, null)

            val maskPaint = Paint().apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                shader = LinearGradient(
                    0f, 0f, 0f, reflH,
                    Color.argb((255 * topAlpha).toInt(), 255, 255, 255),
                    Color.TRANSPARENT,
                    Shader.TileMode.CLAMP
                )
            }
            rcCanvas.drawRect(0f, 0f, w, reflH, maskPaint)

            rcCanvas.restoreToCount(save)
        }

        canvas.drawBitmap(ic, 0f, 0f, null)
        if (rc != null && !rc.isRecycled) {
            canvas.drawBitmap(rc, 0f, ic.height + gapPx.toFloat(), null)
        }
    }
}
