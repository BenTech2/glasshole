package com.glasshole.plugin.gallery2.glass

import android.content.Context
import android.graphics.Color
import android.os.AsyncTask
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * RecyclerView adapter for the Cover Flow carousel. Each item is a
 * square thumbnail rendered through [ReflectionImageView] so the
 * card's rounded-rect tile and the photo behind it both mirror into
 * the reflection below — same look as the base-app cover-flow
 * drawers (app drawer / settings drawer). A small play-triangle
 * overlay sits on top of the card for videos; it doesn't reflect
 * (separate view, not captured by ReflectionImageView's offscreen
 * pass), which is fine — the bottom of the reflection fades out
 * anyway.
 *
 * Thumbnails load off the UI thread to keep the fling smooth.
 */
class CoverFlowAdapter(
    private val context: Context,
    var items: List<MediaItem>
) : RecyclerView.Adapter<CoverFlowAdapter.Holder>() {

    class Holder(
        val pageRoot: FrameLayout,
        val image: ReflectionImageView,
        val playOverlay: TextView
    ) : RecyclerView.ViewHolder(pageRoot)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        // ViewPager2 requires each page's root to be MATCH_PARENT so the
        // transformer can scale the full slot. The card itself is a fixed
        // square centered in the slot.
        val density = parent.resources.displayMetrics.density
        val cardPx = (CARD_SIZE_DP * density).toInt()

        val pageRoot = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            // The reflection draws below the ReflectionImageView's
            // measured bounds; clipChildren / clipToPadding both off
            // so the page root doesn't clip it.
            clipChildren = false
            clipToPadding = false
        }

        // Anchor the card to the bottom of the page with a fixed
        // bottomMargin — that lands the photo's bottom edge right
        // where the reflection meets the title chip, instead of
        // floating in the page's vertical middle.
        val bottomMarginPx = (CARD_BOTTOM_MARGIN_DP * density).toInt()

        val image = ReflectionImageView(context).apply {
            // FIT_CENTER, not CENTER_CROP — show the whole frame
            // inside the card with empty space on the short axis for
            // non-square photos, rather than cropping the edges.
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(
                cardPx, cardPx,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                bottomMargin = bottomMarginPx
            }
            // No background tile — unlike the app / settings drawers'
            // icon tiles, gallery thumbnails read fine as the photo
            // itself against the black activity background, and the
            // reflection picks up only the photo (no rounded frame
            // mirroring along with it).
        }
        pageRoot.addView(image)

        // Play triangle for videos — sibling of the ReflectionImageView,
        // matched in size + position so it overlays the card cleanly.
        val playOverlay = TextView(context).apply {
            text = "▶"
            setTextColor(Color.WHITE)
            textSize = 36f
            gravity = Gravity.CENTER
            setBackgroundColor(0x66000000)
            layoutParams = FrameLayout.LayoutParams(
                cardPx, cardPx,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                bottomMargin = bottomMarginPx
            }
            visibility = View.GONE
        }
        pageRoot.addView(playOverlay)

        return Holder(pageRoot, image, playOverlay)
    }

    companion object {
        // Matches the app drawer's icon tile (160dp square). With the
        // cover-flow's center emphasis ×1.22 the focused card peaks
        // at ~195dp; the reflection extends another ~75dp below, and
        // the title chip sits at marginBottom=30dp from the screen
        // edge — overlap with the bottom of the reflection is fine
        // because the gradient fades there.
        private const val CARD_SIZE_DP = 160
        /** Bottom margin pinning the card to the bottom of the page.
         *  Sized so the bottom of the photo lands roughly where the
         *  reflection meets the title chip — the card sits on the
         *  glass surface instead of floating mid-page. ≈ chip
         *  marginBottom + chip height + reflection visible height. */
        private const val CARD_BOTTOM_MARGIN_DP = 130
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.image.setImageDrawable(null)
        holder.playOverlay.visibility = if (item.isVideo) View.VISIBLE else View.GONE
        // AsyncTask.THREAD_POOL_EXECUTOR — default is SERIAL which queues
        // all thumbnail loads one-at-a-time and made the carousel freeze
        // for a noticeable beat on open.
        ThumbnailTask(context, item, holder.image)
            .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR)
        // No click listener: a setOnClickListener on the card marks it
        // clickable, and EE2's touchpad driver then suppresses the
        // swipe → TAB keycode conversion. Taps are handled at the
        // Activity level via KEYCODE_DPAD_CENTER / MotionEvent.
    }

    override fun getItemCount(): Int = items.size

    private class ThumbnailTask(
        private val context: Context,
        private val item: MediaItem,
        private val target: ImageView
    ) : AsyncTask<Void, Void, android.graphics.Bitmap?>() {
        private val tag: String = item.file.absolutePath
        init { target.tag = tag }

        override fun doInBackground(vararg params: Void?): android.graphics.Bitmap? {
            return MediaScanner.loadThumbnail(context, item)
        }

        override fun onPostExecute(result: android.graphics.Bitmap?) {
            if (target.tag == tag && result != null) {
                target.setImageBitmap(result)
            }
        }
    }
}
