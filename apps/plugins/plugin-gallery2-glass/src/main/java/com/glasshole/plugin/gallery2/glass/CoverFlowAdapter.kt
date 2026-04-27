package com.glasshole.plugin.gallery2.glass

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.AsyncTask
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * RecyclerView adapter for the Cover Flow carousel. Each item is a square
 * thumbnail with an optional play-icon overlay for videos. Thumbnails load
 * off the UI thread to keep the fling smooth.
 */
class CoverFlowAdapter(
    private val context: Context,
    var items: List<MediaItem>
) : RecyclerView.Adapter<CoverFlowAdapter.Holder>() {

    class Holder(
        val pageRoot: FrameLayout,
        val card: FrameLayout,
        val image: ImageView,
        val label: TextView
    ) : RecyclerView.ViewHolder(pageRoot)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        // ViewPager2 requires each page's root to be MATCH_PARENT so the
        // transformer can scale the full slot. The card itself is a fixed
        // square centered in the slot — otherwise a MATCH_PARENT card
        // consumes the entire screen and the cover-flow neighbors get
        // dwarfed by one giant center thumbnail.
        val density = parent.resources.displayMetrics.density
        val cardPx = (CARD_SIZE_DP * density).toInt()

        val pageRoot = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val card = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                cardPx, cardPx, Gravity.CENTER
            )
            background = GradientDrawable().apply {
                cornerRadius = 6f * density
                setColor(0xFF1A1A1A.toInt())
                setStroke((1f * density).toInt(), 0xFF333333.toInt())
            }
        }

        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        card.addView(image)

        // Small play triangle overlay for videos
        val label = TextView(context).apply {
            text = "▶"
            setTextColor(Color.WHITE)
            textSize = 36f
            gravity = Gravity.CENTER
            setBackgroundColor(0x66000000)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }
        card.addView(label)

        pageRoot.addView(card)
        return Holder(pageRoot, card, image, label)
    }

    companion object {
        // 120dp = 180px at 1.5× density (XE / EE2's 240dpi 360-px-tall
        // screens). With cover-flow center emphasis ×1.22 the focused
        // card peaks at ~220px, leaving the title strip + a sliver of
        // the neighbors visible. 180dp was too tall for either glass —
        // the centered card filled the screen vertically and dwarfed the
        // tilted neighbors into invisibility.
        private const val CARD_SIZE_DP = 120
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.image.setImageDrawable(null)
        holder.label.visibility = if (item.isVideo) View.VISIBLE else View.GONE
        // AsyncTask.THREAD_POOL_EXECUTOR — default is SERIAL which queues
        // all thumbnail loads one-at-a-time and made the carousel freeze
        // for a noticeable beat on open.
        ThumbnailTask(context, item, holder.image)
            .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR)
        // No click listener: a setOnClickListener on the card marks it
        // clickable, and EE2's touchpad driver then suppresses the
        // swipe → TAB keycode conversion. Taps are handled at the
        // Activity level via KEYCODE_DPAD_CENTER instead.
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
