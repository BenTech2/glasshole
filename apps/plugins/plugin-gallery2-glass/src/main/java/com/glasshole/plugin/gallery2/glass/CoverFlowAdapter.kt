package com.glasshole.plugin.gallery2.glass

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.AsyncTask
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
    var items: List<MediaItem>,
    private val onClick: (MediaItem) -> Unit
) : RecyclerView.Adapter<CoverFlowAdapter.Holder>() {

    class Holder(
        val pageRoot: FrameLayout,
        val card: FrameLayout,
        val image: ImageView,
        val label: TextView
    ) : RecyclerView.ViewHolder(pageRoot)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        // ViewPager2 requires each page's root to be MATCH_PARENT. The
        // transformer scales this whole page; since the ViewPager2 itself
        // already uses horizontal padding to size each page to the carousel
        // column, the page root IS the card.
        val pageRoot = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val density = parent.resources.displayMetrics.density
        val card = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            background = GradientDrawable().apply {
                cornerRadius = 6f * density
                setColor(0xFF1A1A1A.toInt())
                setStroke((1f * density).toInt(), 0xFF333333.toInt())
            }
            val margin = (8 * density).toInt()
            (layoutParams as FrameLayout.LayoutParams).setMargins(margin, margin, margin, margin)
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

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.image.setImageDrawable(null)
        holder.label.visibility = if (item.isVideo) View.VISIBLE else View.GONE
        ThumbnailTask(context, item, holder.image).execute()
        holder.card.setOnClickListener { onClick(item) }
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
