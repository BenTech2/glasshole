package com.glasshole.glassxe.home

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
 * normal content — a vertically-mirrored copy of the icon (and its
 * background tile) faded out by a top-to-bottom alpha gradient.
 *
 * The reflection renders *outside* the view's measured bounds, so the
 * direct parent must have clipChildren="false" for it to be visible,
 * and the page layout must reserve vertical space between this view
 * and whatever sits below it (see page_app.xml).
 *
 * Implementation: the view's normal drawing is captured into one
 * offscreen Bitmap, and the masked reflection is composed into a
 * second offscreen Bitmap using saveLayer + PorterDuff.Mode.DST_IN.
 * Both Bitmaps are then blitted to the view's canvas via plain
 * drawBitmap — the view's (potentially hw-accelerated) canvas never
 * sees the saveLayer/PorterDuff pair, which on KitKat leaves dirty
 * framebuffer pixels past the view's bounds (visible as artifacts
 * around overlay views like the cover-flow's label chip).
 */
class ReflectionImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    /** Reflection visible height as a fraction of icon height. */
    private val reflectionFrac = 0.45f
    /** Vertical gap between the icon and the top of its reflection. */
    private val gapPx = (resources.displayMetrics.density * 4f).toInt()
    /** Opacity at the top of the reflection. Fades linearly to 0 at bottom. */
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

        // Render the view (background + drawable) into the icon cache.
        // Canvas(Bitmap) is a software canvas, so super.draw can do
        // whatever it wants without affecting the view's hw canvas.
        ic.eraseColor(Color.TRANSPARENT)
        super.draw(Canvas(ic))

        // Build the masked reflection on its own software canvas. The
        // saveLayer + PorterDuff pass here is safe because the Canvas
        // wraps a Bitmap — running the same pass on the view's hw
        // canvas (as we used to) leaves dirty framebuffer pixels past
        // the view bounds, which showed up as artifacts around the
        // label chip on KitKat.
        val rc = reflectionCache
        if (rc != null && !rc.isRecycled) {
            rc.eraseColor(Color.TRANSPARENT)
            val rcCanvas = Canvas(rc)
            val h = ic.height.toFloat()
            val reflH = rc.height.toFloat()
            val w = rc.width.toFloat()

            val save = rcCanvas.saveLayer(0f, 0f, w, reflH, null, Canvas.ALL_SAVE_FLAG)

            // preScale(1, -1) flips Y, postTranslate(0, h) puts the
            // (now-flipped) image so its bottom-original edge sits at
            // y=0. With reflH < h, the visible top portion of the
            // reflection cache shows the bottom of the original
            // mirrored — exactly the cover-flow effect.
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

        // Blit both pre-rendered bitmaps onto the view's canvas. Plain
        // drawBitmap doesn't trigger the hw-accelerated saveLayer
        // codepath, so no artifacts.
        canvas.drawBitmap(ic, 0f, 0f, null)
        if (rc != null && !rc.isRecycled) {
            canvas.drawBitmap(rc, 0f, ic.height + gapPx.toFloat(), null)
        }
    }
}
