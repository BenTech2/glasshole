package com.glasshole.glassxe.home

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.speech.RecognizerIntent
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.glasshole.glassxe.BluetoothListenerService
import com.glasshole.glassxe.R

/**
 * Paginated notification drawer — opens when the user taps the
 * Notification card in Home. Page 0 is a virtual "Dismiss all" card;
 * pages 1..N are the active notifications, newest first.
 *
 * Gestures (EE1 receives raw SOURCE_TOUCHPAD motion events, EE2
 * receives TAB / BACK / DPAD_CENTER key events — both paths route to
 * the same helpers):
 *   swipe forward / back  → next / previous notification page
 *   tap                   → show per-notification options overlay
 *     in overlay:
 *       swipe fwd / back  → cycle between the notif's actions + Dismiss
 *       tap               → execute the selected option
 *       swipe down        → close overlay (back to notification)
 *   swipe down (no overlay) → close drawer, back to Home
 */
class NotificationDrawerActivity : Activity() {

    companion object {
        private const val TAG = "NotifDrawer"
        private const val SYNTH_DISMISS_ID = "__dismiss__"
        private const val SYNTH_DISMISS_ALL_ID = "__dismiss_all__"
        private const val REQUEST_VOICE = 101
        private const val VIEW_TYPE_DISMISS_ALL = 0
        private const val VIEW_TYPE_NOTIFICATION = 1
        private const val DISMISS_ALL_OFFSET = 1
    }

    private lateinit var pager: ViewPager2
    private lateinit var dots: DotsView
    private lateinit var optionsOverlay: LinearLayout
    private lateinit var optionsRow: LinearLayout
    private lateinit var adapter: NotifPageAdapter

    private var overlayVisible: Boolean = false
    private var overlaySelected: Int = 0
    private var overlayOptions: List<NotifAction> = emptyList()
    private var overlayViews: List<TextView> = emptyList()
    private var pendingReplyNotifKey: String? = null
    private var pendingReplyActionId: String? = null

    // EE1 touchpad swipe tracking. Same pattern as HomeActivity.
    private var downX = 0f
    private var downY = 0f

    private val storeListener: () -> Unit = {
        runOnUiThread { refreshFromStore() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notification_drawer)

        pager = findViewById(R.id.notifPager)
        dots = findViewById(R.id.notifDots)
        optionsOverlay = findViewById(R.id.notifOptions)
        optionsRow = findViewById(R.id.optionsRow)

        adapter = NotifPageAdapter()
        pager.adapter = adapter
        pager.isUserInputEnabled = false
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                dots.setPages(adapter.itemCount, position, leadingIsAction = true)
            }
        })

        refreshFromStore()
        if (adapter.itemCount == 0) {
            finish()
        }
    }

    override fun onStart() {
        super.onStart()
        NotificationStore.addListener(storeListener)
    }

    override fun onStop() {
        super.onStop()
        NotificationStore.removeListener(storeListener)
    }

    private fun refreshFromStore() {
        val list = NotificationStore.all().asReversed()
        adapter.submit(list)
        if (list.isEmpty()) {
            finish()
            return
        }
        val totalPages = adapter.itemCount
        val preserved = pager.currentItem
        val current = when {
            preserved <= 0 -> DISMISS_ALL_OFFSET
            preserved >= totalPages -> totalPages - 1
            else -> preserved
        }
        pager.setCurrentItem(current, false)
        dots.setPages(totalPages, current, leadingIsAction = true)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (overlayVisible) return handleOverlayKey(keyCode, event)

        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> { finish(); true }
            KeyEvent.KEYCODE_TAB -> {
                navigatePage(if (event?.isShiftPressed == true) -1 else 1); true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { navigatePage(1); true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { navigatePage(-1); true }
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> true
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { showOverlay(); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun handleOverlayKey(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> { hideOverlay(); true }
            KeyEvent.KEYCODE_TAB -> {
                cycleOverlaySelection(if (event?.isShiftPressed == true) -1 else 1); true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { cycleOverlaySelection(1); true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { cycleOverlaySelection(-1); true }
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> true
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                executeOverlaySelection(); true
            }
            else -> true
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (handleGesture(event)) return true
        return super.dispatchTouchEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        if (event != null && handleGesture(event)) return true
        return super.onGenericMotionEvent(event)
    }

    private fun handleGesture(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                val absDx = kotlin.math.abs(dx); val absDy = kotlin.math.abs(dy)
                if (dy > 120 && absDy > absDx * 1.3f) {
                    if (overlayVisible) hideOverlay() else finish()
                    return true
                }
                if (absDx > 60 && absDx > absDy) {
                    val dir = if (dx > 0) 1 else -1
                    if (overlayVisible) cycleOverlaySelection(dir) else navigatePage(dir)
                    return true
                }
                if (absDx < 25 && absDy < 25) {
                    if (overlayVisible) executeOverlaySelection() else showOverlay()
                    return true
                }
            }
        }
        return false
    }

    private fun navigatePage(delta: Int) {
        val target = (pager.currentItem + delta).coerceIn(0, (adapter.itemCount - 1).coerceAtLeast(0))
        if (target != pager.currentItem) pager.setCurrentItem(target, true)
    }

    private fun showOverlay() {
        val position = pager.currentItem
        overlayOptions = if (position == 0) {
            listOf(
                NotifAction(SYNTH_DISMISS_ALL_ID, "Dismiss all", "dismiss_all", null),
                NotifAction(SYNTH_DISMISS_ID, "Cancel", "cancel", null)
            )
        } else {
            val entry = adapter.entryAt(position) ?: return
            entry.actions + NotifAction(SYNTH_DISMISS_ID, "Dismiss", "dismiss", null)
        }
        rebuildOptionsRow()
        overlaySelected = 0
        paintOverlaySelection()
        optionsOverlay.visibility = View.VISIBLE
        overlayVisible = true
    }

    private fun hideOverlay() {
        optionsOverlay.visibility = View.GONE
        overlayVisible = false
    }

    private fun rebuildOptionsRow() {
        optionsRow.removeAllViews()
        val built = mutableListOf<TextView>()
        overlayOptions.forEach { option ->
            val tv = TextView(this).apply {
                text = option.label
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
                typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                setPadding(dp(18), dp(10), dp(18), dp(10))
                gravity = Gravity.CENTER
            }
            optionsRow.addView(tv)
            built.add(tv)
        }
        overlayViews = built
    }

    private fun cycleOverlaySelection(delta: Int) {
        val n = overlayOptions.size
        if (n == 0) return
        overlaySelected = ((overlaySelected + delta) % n + n) % n
        paintOverlaySelection()
    }

    private fun paintOverlaySelection() {
        overlayViews.forEachIndexed { idx, tv ->
            tv.setTextColor(
                if (idx == overlaySelected) 0xFFFFC107.toInt() else 0xFFFFFFFF.toInt()
            )
        }
    }

    private fun executeOverlaySelection() {
        val option = overlayOptions.getOrNull(overlaySelected) ?: return
        if (option.id == SYNTH_DISMISS_ALL_ID) {
            val keys = NotificationStore.all().map { it.key }
            for (key in keys) {
                BluetoothListenerService.instance?.sendNotifDismiss(key)
                NotificationStore.remove(key)
            }
            hideOverlay()
            return
        }
        if (option.type == "cancel") {
            hideOverlay()
            return
        }
        val entry = adapter.entryAt(pager.currentItem) ?: return
        if (option.id == SYNTH_DISMISS_ID) {
            NotificationStore.remove(entry.key)
            BluetoothListenerService.instance?.sendNotifDismiss(entry.key)
            hideOverlay()
            return
        }
        invokeAction(entry.key, option)
    }

    private fun invokeAction(notifKey: String, a: NotifAction) {
        Log.i(TAG, "Action tapped: ${a.id} / ${a.type}")
        when (a.type) {
            "reply" -> startVoiceReply(notifKey, a.id)
            "open_glass_stream" -> {
                val url = a.url
                if (url.isNullOrEmpty()) {
                    toast("No stream URL")
                } else {
                    BluetoothListenerService.instance?.playStreamLocally(url)
                    BluetoothListenerService.instance?.sendNotifAction(notifKey, a.id, null)
                    finish()
                }
            }
            else -> {
                val ok = BluetoothListenerService.instance
                    ?.sendNotifAction(notifKey, a.id, null) ?: false
                val msg = when {
                    !ok -> "Not connected"
                    a.type == "open_phone" -> "Opening on phone"
                    else -> "Sent"
                }
                toast(msg)
                hideOverlay()
            }
        }
    }

    private fun startVoiceReply(notifKey: String, actionId: String) {
        pendingReplyNotifKey = notifKey
        pendingReplyActionId = actionId
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your reply")
            }
            startActivityForResult(intent, REQUEST_VOICE)
        } catch (e: Exception) {
            Log.e(TAG, "Voice recognition unavailable: ${e.message}")
            toast("Voice input unavailable")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VOICE) {
            val notifKey = pendingReplyNotifKey
            val actionId = pendingReplyActionId
            pendingReplyNotifKey = null
            pendingReplyActionId = null
            if (notifKey == null || actionId == null) return
            if (resultCode == RESULT_OK && data != null) {
                val results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                val text = results?.firstOrNull().orEmpty()
                if (text.isEmpty()) { toast("No speech detected"); return }
                val ok = BluetoothListenerService.instance
                    ?.sendNotifAction(notifKey, actionId, text) ?: false
                toast(if (ok) "Reply sent" else "Not connected")
                hideOverlay()
            } else {
                toast("Cancelled")
            }
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private class NotifPageAdapter : RecyclerView.Adapter<PageHolder>() {
        private val items = mutableListOf<NotificationStore.Entry>()

        fun submit(list: List<NotificationStore.Entry>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        private fun notifIndex(position: Int): Int = position - DISMISS_ALL_OFFSET

        fun keyAt(position: Int): String? = entryAt(position)?.key
        fun entryAt(position: Int): NotificationStore.Entry? {
            val idx = notifIndex(position)
            return if (idx < 0) null else items.getOrNull(idx)
        }

        override fun getItemCount(): Int =
            if (items.isEmpty()) 0 else items.size + DISMISS_ALL_OFFSET

        override fun getItemViewType(position: Int): Int =
            if (position == 0) VIEW_TYPE_DISMISS_ALL else VIEW_TYPE_NOTIFICATION

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val layoutId = if (viewType == VIEW_TYPE_DISMISS_ALL)
                R.layout.page_dismiss_all else R.layout.page_notification
            val view = LayoutInflater.from(parent.context)
                .inflate(layoutId, parent, false)
            view.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            return PageHolder(view)
        }

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            if (position == 0) return
            val e = items[notifIndex(position)]
            holder.itemView.findViewById<TextView>(R.id.pageAppText)?.text =
                e.app.ifEmpty { "Notification" }.uppercase()
            holder.itemView.findViewById<TextView>(R.id.pageTitle)?.text = e.title
            holder.itemView.findViewById<TextView>(R.id.pageBody)?.text = e.text
            holder.itemView.findViewById<TextView>(R.id.pageTimestamp)?.text =
                formatTimeAgo(e.timestamp)
            val icon = holder.itemView.findViewById<ImageView>(R.id.pageAppIcon)
            if (e.iconBitmap != null) icon?.setImageBitmap(e.iconBitmap)
            else icon?.setImageDrawable(null)

            val picture = holder.itemView.findViewById<ImageView>(R.id.pagePicture)
            val gradient = holder.itemView.findViewById<View>(R.id.pagePictureGradient)
            val topSpacer = holder.itemView.findViewById<View>(R.id.pageTopSpacer)
            val middleSpacer = holder.itemView.findViewById<View>(R.id.pageMiddleSpacer)
            if (e.pictureBitmap != null) {
                picture?.setImageBitmap(e.pictureBitmap)
                picture?.visibility = View.VISIBLE
                gradient?.visibility = View.VISIBLE
                topSpacer?.visibility = View.VISIBLE
                middleSpacer?.visibility = View.GONE
            } else {
                picture?.setImageDrawable(null)
                picture?.visibility = View.GONE
                gradient?.visibility = View.GONE
                topSpacer?.visibility = View.GONE
                middleSpacer?.visibility = View.VISIBLE
            }
        }

        private fun formatTimeAgo(ts: Long): String {
            if (ts <= 0L) return ""
            val delta = System.currentTimeMillis() - ts
            return when {
                delta < 60_000L -> "now"
                delta < 3_600_000L -> "${delta / 60_000L}m ago"
                delta < 86_400_000L -> "${delta / 3_600_000L}h ago"
                else -> "${delta / 86_400_000L}d ago"
            }
        }
    }

    private class PageHolder(view: View) : RecyclerView.ViewHolder(view)
}
