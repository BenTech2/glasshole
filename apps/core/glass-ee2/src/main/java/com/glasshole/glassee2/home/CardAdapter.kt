package com.glasshole.glassee2.home

import android.content.Context
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.glasshole.glassee2.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * RecyclerView adapter backing the Home ViewPager2. Each position is a
 * [CardType]; the adapter inflates the matching layout and binds data.
 *
 * For M1 only TIME is real; NOTIFICATION and MEDIA are placeholder cards
 * we'll replace in later milestones. NAV is inserted/removed dynamically
 * in M3.
 */
enum class CardType { EXIT, SETTINGS, NOTIFICATION, TIME, MEDIA, NAV }

class CardAdapter(
    private val context: Context
) : RecyclerView.Adapter<CardAdapter.CardHolder>() {

    /** Mutable so we can insert/remove the Nav card at runtime. The EXIT
     *  tile ships in both variants — standalone closes the activity,
     *  launcher hands control back to the stock Glass home. */
    private val cards: MutableList<CardType> = mutableListOf(
        CardType.EXIT, CardType.SETTINGS, CardType.NOTIFICATION,
        CardType.TIME, CardType.MEDIA
    )

    /** Last known media snapshot from the phone, used to paint the Media card. */
    var mediaState: MediaState = MediaState.EMPTY
        private set

    fun setMediaState(state: MediaState) {
        mediaState = state
        val idx = cards.indexOf(CardType.MEDIA)
        if (idx >= 0) notifyItemChanged(idx, PAYLOAD_MEDIA)
    }

    /** Current nav step from Google Maps (empty when no trip active). */
    var navState: NavState = NavState.EMPTY
        private set

    fun setNavState(state: NavState) {
        navState = state
        val idx = cards.indexOf(CardType.NAV)
        if (idx >= 0) notifyItemChanged(idx, PAYLOAD_NAV)
    }

    fun positionOf(type: CardType): Int = cards.indexOf(type)

    fun hasCard(type: CardType): Boolean = cards.contains(type)

    fun cardAt(position: Int): CardType? = cards.getOrNull(position)

    fun addCard(type: CardType, position: Int) {
        if (cards.contains(type)) return
        val pos = position.coerceIn(0, cards.size)
        cards.add(pos, type)
        notifyItemInserted(pos)
    }

    fun removeCard(type: CardType) {
        val idx = cards.indexOf(type)
        if (idx < 0) return
        cards.removeAt(idx)
        notifyItemRemoved(idx)
    }

    override fun getItemCount(): Int = cards.size

    override fun getItemViewType(position: Int): Int = cards[position].ordinal

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardHolder {
        val inflater = LayoutInflater.from(parent.context)
        val type = CardType.values()[viewType]
        val layout = when (type) {
            CardType.EXIT -> R.layout.card_exit
            CardType.SETTINGS -> R.layout.card_settings
            CardType.TIME -> R.layout.card_time
            CardType.MEDIA -> R.layout.card_media
            CardType.NAV -> R.layout.card_nav
            CardType.NOTIFICATION -> R.layout.card_notification
        }
        val view = inflater.inflate(layout, parent, false)
        // ViewPager2 pages must be match_parent on both axes.
        view.layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        // Notification card: clip the front card to its rounded-top
        // outline so the full-bleed picture child doesn't square out
        // the corners. ViewOutlineProvider is API 21+; EE2 runs API 27+
        // so this is always available, but the guard is cheap.
        if (type == CardType.NOTIFICATION) {
            view.findViewById<View>(R.id.notifFrontCard)?.let { card ->
                val radiusPx = card.resources.displayMetrics.density * 6f
                card.outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(v: View, outline: android.graphics.Outline) {
                        // Extending bottom past the view rounds only the top
                        // two corners — the rest is clipped by the screen edge.
                        outline.setRoundRect(
                            0, 0, v.width, v.height + radiusPx.toInt(), radiusPx
                        )
                    }
                }
                card.clipToOutline = true
            }
        }
        return CardHolder(view, type)
    }

    override fun onBindViewHolder(holder: CardHolder, position: Int) {
        when (holder.type) {
            CardType.EXIT -> Unit  // fully static layout
            CardType.SETTINGS -> Unit  // fully static layout
            CardType.TIME -> bindTime(holder)
            CardType.NOTIFICATION -> bindNotification(holder)
            CardType.MEDIA -> bindMedia(holder)
            CardType.NAV -> bindNav(holder)
        }
    }

    /** Rebinds only the time card if it's in the current window. */
    fun refreshTimeCard() {
        val idx = cards.indexOf(CardType.TIME)
        if (idx >= 0) notifyItemChanged(idx, PAYLOAD_TIME_TICK)
    }

    /** Rebinds only the media progress bar (for smooth per-tick scrubber motion). */
    fun refreshMediaProgress() {
        val idx = cards.indexOf(CardType.MEDIA)
        if (idx >= 0) notifyItemChanged(idx, PAYLOAD_MEDIA_TICK)
    }

    override fun onBindViewHolder(holder: CardHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty()) {
            if (payloads.contains(PAYLOAD_TIME_TICK) && holder.type == CardType.TIME) {
                bindTime(holder); return
            }
            if (holder.type == CardType.MEDIA &&
                (payloads.contains(PAYLOAD_MEDIA) || payloads.contains(PAYLOAD_MEDIA_TICK))) {
                if (payloads.contains(PAYLOAD_MEDIA)) bindMedia(holder)
                else bindMediaProgress(holder)
                return
            }
            if (holder.type == CardType.NAV && payloads.contains(PAYLOAD_NAV)) {
                bindNav(holder); return
            }
            if (holder.type == CardType.NOTIFICATION && payloads.contains(PAYLOAD_NOTIF)) {
                bindNotification(holder); return
            }
        }
        onBindViewHolder(holder, position)
    }

    private fun bindTime(holder: CardHolder) {
        val timeText = holder.itemView.findViewById<TextView>(R.id.timeText)
        val ampmText = holder.itemView.findViewById<TextView>(R.id.ampmText)
        val dateText = holder.itemView.findViewById<TextView>(R.id.dateText)
        val batteryText = holder.itemView.findViewById<TextView>(R.id.batteryText)
        if (timeText == null) return

        val tzId = HomePrefs.getTimezone(context)
        val tz = if (tzId.isNotEmpty()) TimeZone.getTimeZone(tzId) else TimeZone.getDefault()
        val now = Calendar.getInstance(tz).time

        val timeFmt = SimpleDateFormat("h:mm", Locale.getDefault()).apply { timeZone = tz }
        val ampmFmt = SimpleDateFormat("a", Locale.getDefault()).apply { timeZone = tz }
        val dateFmt = SimpleDateFormat("EEEE, MMM d", Locale.getDefault()).apply { timeZone = tz }

        timeText.text = timeFmt.format(now)
        ampmText.text = ampmFmt.format(now)
        dateText.text = dateFmt.format(now)

        val status = readBatteryStatus()
        batteryText?.text = when {
            status == null -> ""
            status.charging -> "⚡ ${status.percent}%"
            else -> "${status.percent}%"
        }

        val wifi = holder.itemView.findViewById<ImageView>(R.id.wifiStatusIcon)
        val phone = holder.itemView.findViewById<ImageView>(R.id.phoneStatusIcon)
        wifi?.visibility = if (isWifiConnected()) View.VISIBLE else View.GONE
        phone?.visibility = if (isPhoneConnected()) View.VISIBLE else View.GONE
    }

    private data class BatteryStatus(val percent: Int, val charging: Boolean)

    private fun readBatteryStatus(): BatteryStatus? {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
                ?: return null
            val pct = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (pct !in 0..100) return null
            BatteryStatus(pct, bm.isCharging)
        } catch (_: Exception) {
            null
        }
    }

    private fun isWifiConnected(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? android.net.ConnectivityManager ?: return false
            val nw = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(nw) ?: return false
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) &&
                caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            false
        }
    }

    private fun isPhoneConnected(): Boolean {
        return com.glasshole.glassee2.BluetoothListenerService.instance?.isPhoneConnected == true
    }

    private fun bindMedia(holder: CardHolder) {
        val content = holder.itemView.findViewById<LinearLayout>(R.id.mediaContent) ?: return
        val waiting = holder.itemView.findViewById<TextView>(R.id.waitingText) ?: return
        val state = mediaState
        if (!state.hasSession) {
            content.visibility = View.GONE
            waiting.visibility = View.VISIBLE
            return
        }
        content.visibility = View.VISIBLE
        waiting.visibility = View.GONE

        holder.itemView.findViewById<TextView>(R.id.titleText)?.text =
            state.title.ifEmpty { "(unknown)" }
        holder.itemView.findViewById<TextView>(R.id.artistText)?.text = state.artist
        holder.itemView.findViewById<TextView>(R.id.albumText)?.text = state.album
        holder.itemView.findViewById<TextView>(R.id.appText)?.text = state.appName
        holder.itemView.findViewById<ImageView>(R.id.stateIcon)
            ?.setImageResource(if (state.playing) R.drawable.ic_play else R.drawable.ic_pause)

        val art = holder.itemView.findViewById<ImageView>(R.id.albumArt)
        if (state.artBitmap != null) {
            art?.setImageBitmap(state.artBitmap)
        } else {
            art?.setImageDrawable(null)
        }

        bindMediaProgress(holder)
    }

    private fun bindMediaProgress(holder: CardHolder) {
        val state = mediaState
        val bar = holder.itemView.findViewById<ProgressBar>(R.id.progressBar) ?: return
        val elapsedT = holder.itemView.findViewById<TextView>(R.id.elapsedText)
        val durationT = holder.itemView.findViewById<TextView>(R.id.durationText)

        val duration = state.durationMs
        val base = state.positionMs
        val since = if (state.playing) {
            SystemClock.elapsedRealtime() - state.positionCapturedAtRealtime
        } else 0L
        val current = (base + since).coerceAtLeast(0L)

        if (duration <= 0) {
            bar.progress = 0
            elapsedT?.text = formatTime(current)
            durationT?.text = "--:--"
            return
        }
        val clampedCurrent = current.coerceAtMost(duration)
        bar.progress = ((clampedCurrent.toDouble() / duration) * 1000).toInt().coerceIn(0, 1000)
        elapsedT?.text = formatTime(clampedCurrent)
        durationT?.text = formatTime(duration)
    }

    private fun formatTime(ms: Long): String {
        if (ms < 0) return "0:00"
        val totalSec = ms / 1000
        return String.format("%d:%02d", totalSec / 60, totalSec % 60)
    }

    /** Poke the notification card to rebind. Called by HomeActivity's NotificationStore listener. */
    fun refreshNotificationCard() {
        val idx = cards.indexOf(CardType.NOTIFICATION)
        if (idx >= 0) notifyItemChanged(idx, PAYLOAD_NOTIF)
    }

    private fun bindNotification(holder: CardHolder) {
        val latest = NotificationStore.latest()
        val total = NotificationStore.count()
        val front = holder.itemView.findViewById<View>(R.id.notifFrontCard)
        val empty = holder.itemView.findViewById<TextView>(R.id.notifEmpty)
        val picture = holder.itemView.findViewById<ImageView>(R.id.notifPicture)
        val gradient = holder.itemView.findViewById<View>(R.id.notifPictureGradient)
        val stack1 = holder.itemView.findViewById<View>(R.id.notifStack1)
        val stack2 = holder.itemView.findViewById<View>(R.id.notifStack2)
        val stackCount = holder.itemView.findViewById<TextView>(R.id.notifStackCount)
        if (latest == null) {
            front?.visibility = View.GONE
            empty?.visibility = View.VISIBLE
            picture?.visibility = View.GONE
            gradient?.visibility = View.GONE
            stack1?.visibility = View.GONE
            stack2?.visibility = View.GONE
            stackCount?.visibility = View.GONE
            return
        }
        front?.visibility = View.VISIBLE
        empty?.visibility = View.GONE

        // Time Machine slivers: peek out the top of the front card to imply
        // a stack. Decorative only — drawer is still tap-to-open.
        stack1?.visibility = if (total >= 2) View.VISIBLE else View.GONE
        stack2?.visibility = if (total >= 3) View.VISIBLE else View.GONE
        if (total >= 2) {
            stackCount?.visibility = View.VISIBLE
            stackCount?.text = "$total notifications"
        } else {
            stackCount?.visibility = View.GONE
        }

        val topSpacer = holder.itemView.findViewById<View>(R.id.notifTopSpacer)
        val middleSpacer = holder.itemView.findViewById<View>(R.id.notifMiddleSpacer)
        if (latest.pictureBitmap != null) {
            picture?.setImageBitmap(latest.pictureBitmap)
            picture?.visibility = View.VISIBLE
            gradient?.visibility = View.VISIBLE
            // Picture layout: push title + body down so they sit over the
            // dark gradient, matching the popup card.
            topSpacer?.visibility = View.VISIBLE
            middleSpacer?.visibility = View.GONE
        } else {
            picture?.setImageDrawable(null)
            picture?.visibility = View.GONE
            gradient?.visibility = View.GONE
            // Text-only: title at top, middle spacer pushes footer down.
            topSpacer?.visibility = View.GONE
            middleSpacer?.visibility = View.VISIBLE
        }

        holder.itemView.findViewById<TextView>(R.id.notifAppText)?.text =
            latest.app.ifEmpty { "Notifications" }.uppercase()
        holder.itemView.findViewById<TextView>(R.id.notifTitle)?.text = latest.title
        holder.itemView.findViewById<TextView>(R.id.notifBody)?.text = latest.text
        holder.itemView.findViewById<TextView>(R.id.notifTimestamp)?.text =
            formatTimeAgo(latest.timestamp)

        val icon = holder.itemView.findViewById<ImageView>(R.id.notifAppIcon)
        if (latest.iconBitmap != null) icon?.setImageBitmap(latest.iconBitmap)
        else icon?.setImageDrawable(null)
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

    private fun bindNav(holder: CardHolder) {
        val s = navState
        holder.itemView.findViewById<TextView>(R.id.navDistanceText)?.text = s.distance
        holder.itemView.findViewById<TextView>(R.id.navInstructionText)?.text = s.instruction
        holder.itemView.findViewById<TextView>(R.id.navEtaText)?.text = s.eta
        val icon = holder.itemView.findViewById<ImageView>(R.id.navTurnIcon)
        if (s.iconBitmap != null) icon?.setImageBitmap(s.iconBitmap) else icon?.setImageDrawable(null)

        val bar = holder.itemView.findViewById<ProgressBar>(R.id.navProgressBar)
        if (bar != null) {
            if (s.active && s.progress >= 0.0) {
                bar.visibility = View.VISIBLE
                bar.progress = (s.progress * 1000).toInt().coerceIn(0, 1000)
            } else {
                bar.visibility = View.GONE
            }
        }
    }

    class CardHolder(view: View, val type: CardType) : RecyclerView.ViewHolder(view)

    companion object {
        private const val PAYLOAD_TIME_TICK = "tick"
        private const val PAYLOAD_MEDIA = "media"
        private const val PAYLOAD_MEDIA_TICK = "media_tick"
        private const val PAYLOAD_NAV = "nav"
        private const val PAYLOAD_NOTIF = "notif"
    }
}
