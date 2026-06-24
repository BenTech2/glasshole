package com.glasshole.glassxe.home

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.glasshole.glassxe.R
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
enum class CardType { ABOUT, EXIT, SETTINGS, NOTIFICATION, TIME, MEDIA, NAV }

class CardAdapter(
    private val context: Context
) : RecyclerView.Adapter<CardAdapter.CardHolder>() {

    /** Mutable so we can insert/remove the Nav card at runtime. The EXIT
     *  tile ships in both variants — standalone closes the activity,
     *  launcher hands control back to the stock Glass home. */
    private val cards: MutableList<CardType> = mutableListOf(
        CardType.ABOUT, CardType.EXIT, CardType.SETTINGS, CardType.NOTIFICATION,
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
            CardType.ABOUT -> R.layout.card_about
            CardType.EXIT -> R.layout.card_exit
            CardType.SETTINGS -> R.layout.card_settings
            CardType.TIME -> R.layout.card_time
            CardType.MEDIA -> R.layout.card_media
            CardType.NAV -> R.layout.card_nav
            CardType.NOTIFICATION -> R.layout.card_notification
        }
        val view = inflater.inflate(layout, parent, false)
        view.layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        return CardHolder(view, type)
    }

    override fun onBindViewHolder(holder: CardHolder, position: Int) {
        when (holder.type) {
            CardType.ABOUT -> bindAbout(holder)
            CardType.EXIT -> Unit  // fully static layout
            CardType.SETTINGS -> Unit  // fully static layout
            CardType.TIME -> bindTime(holder)
            CardType.NOTIFICATION -> bindNotification(holder)
            CardType.MEDIA -> bindMedia(holder)
            CardType.NAV -> bindNav(holder)
        }
    }

    /** Fill in the About card with version + build counter + Wi-Fi MAC,
     *  Bluetooth MAC and current IP address. Useful when something on
     *  the BT pipe acts up — you can read the MACs straight off glass
     *  without ADB. See EE2 copy for the design note. */
    private fun bindAbout(holder: CardHolder) {
        val versionLine = holder.itemView.findViewById<TextView>(R.id.aboutVersionLine)
        val network = holder.itemView.findViewById<TextView>(R.id.aboutNetwork)
        val pm = context.packageManager
        val versionName = try {
            pm.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (_: Exception) { "?" }
        val versionCode = try {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, 0).versionCode
        } catch (_: Exception) { 0 }
        versionLine?.text = "v$versionName · build $versionCode"
        network?.text = com.glasshole.glassxe.NetworkInfo.summary(context)
    }

    fun refreshTimeCard() {
        val idx = cards.indexOf(CardType.TIME)
        if (idx >= 0) notifyItemChanged(idx, PAYLOAD_TIME_TICK)
    }

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

        val prefs = context.getSharedPreferences(
            com.glasshole.glassxe.BaseSettings.PREFS, Context.MODE_PRIVATE
        )
        val showPercent = prefs.getBoolean(
            com.glasshole.glassxe.BaseSettings.KEY_SHOW_BATTERY_PERCENT, true
        )
        val swap = prefs.getBoolean(
            com.glasshole.glassxe.BaseSettings.KEY_SWAP_TOP_BAR, false
        )

        val status = readBatteryStatus()
        batteryText?.let { tv ->
            if (!showPercent || status == null) {
                tv.visibility = View.GONE
            } else {
                tv.visibility = View.VISIBLE
                tv.text = if (status.charging) "⚡ ${status.percent}%" else "${status.percent}%"
            }
        }
        val batteryIcon = holder.itemView.findViewById<BatteryIndicatorView>(R.id.batteryIcon)
        if (status == null) {
            batteryIcon?.visibility = View.GONE
        } else {
            batteryIcon?.visibility = View.VISIBLE
            batteryIcon?.setBattery(status.percent, status.charging)
        }

        applyTopBarSwap(holder.itemView, swap)
        bindWeather(holder.itemView)

        val wifi = holder.itemView.findViewById<ImageView>(R.id.wifiStatusIcon)
        val phone = holder.itemView.findViewById<ImageView>(R.id.phoneStatusIcon)
        wifi?.visibility = if (isWifiConnected()) View.VISIBLE else View.GONE
        phone?.visibility = if (isPhoneConnected()) View.VISIBLE else View.GONE
    }

    /** See EE2 copy for the design note. */
    private fun bindWeather(root: View) {
        val chip = root.findViewById<View>(R.id.weatherChip) ?: return
        val icon = root.findViewById<ImageView>(R.id.weatherIcon)
        val tempT = root.findViewById<TextView>(R.id.weatherTemp)
        val hiLoT = root.findViewById<TextView>(R.id.weatherHiLo)
        val aqiT = root.findViewById<TextView>(R.id.weatherAqi)
        val raw = context.getSharedPreferences("glasshole_weather", Context.MODE_PRIVATE)
            .getString("payload", null)
        if (raw == null) { chip.visibility = View.GONE; return }
        val obj = try { org.json.JSONObject(raw) } catch (_: Exception) {
            chip.visibility = View.GONE; return
        }
        val ts = obj.optLong("ts", 0L) * 1000L
        if (ts > 0 && System.currentTimeMillis() - ts > 6L * 60 * 60 * 1000) {
            chip.visibility = View.GONE; return
        }
        val temp = obj.optInt("temp", Int.MIN_VALUE)
        if (temp == Int.MIN_VALUE) { chip.visibility = View.GONE; return }
        val high = obj.optInt("high", Int.MIN_VALUE)
        val low = obj.optInt("low", Int.MIN_VALUE)
        val code = obj.optInt("code", -1)
        val units = obj.optString("units", "F")
        val isDay = obj.optBoolean("isDay", true)
        chip.visibility = View.VISIBLE
        tempT?.text = "$temp°$units"
        hiLoT?.text = if (high != Int.MIN_VALUE && low != Int.MIN_VALUE)
            "H $high°  L $low°" else ""
        icon?.setImageResource(weatherIconFor(code, isDay))
        val aqi = if (obj.has("aqi")) obj.optInt("aqi", -1) else -1
        bindAqi(aqiT, aqi)
    }

    /** See EE2 copy for the design note. */
    private fun bindAqi(view: TextView?, aqi: Int) {
        view ?: return
        if (aqi < 0) { view.visibility = View.GONE; return }
        view.visibility = View.VISIBLE
        val (label, color) = when {
            aqi <= 50 -> "Good" to 0xFFA5D6A7.toInt()
            aqi <= 100 -> "Moderate" to 0xFFFFF59D.toInt()
            aqi <= 150 -> "Unhealthy SG" to 0xFFFFB74D.toInt()
            aqi <= 200 -> "Unhealthy" to 0xFFEF5350.toInt()
            aqi <= 300 -> "Very Unhealthy" to 0xFFAB47BC.toInt()
            else -> "Hazardous" to 0xFF8D6E63.toInt()
        }
        view.text = "AQI $aqi · $label"
        view.setTextColor(color)
    }

    private fun weatherIconFor(code: Int, @Suppress("UNUSED_PARAMETER") isDay: Boolean): Int = when (code) {
        0, 1 -> R.drawable.ic_weather_clear
        2 -> R.drawable.ic_weather_partly_cloudy
        3 -> R.drawable.ic_weather_cloudy
        45, 48 -> R.drawable.ic_weather_fog
        in 51..67 -> R.drawable.ic_weather_rain
        in 71..77 -> R.drawable.ic_weather_snow
        in 80..82 -> R.drawable.ic_weather_rain
        in 85..86 -> R.drawable.ic_weather_snow
        in 95..99 -> R.drawable.ic_weather_thunder
        else -> R.drawable.ic_weather_partly_cloudy
    }

    /** See EE2 copy for the design note. */
    private fun applyTopBarSwap(root: View, swap: Boolean) {
        val statusRow = root.findViewById<View>(R.id.statusIconsRow) ?: return
        val batteryRow = root.findViewById<View>(R.id.batteryRow) ?: return
        val margin = (16f * context.resources.displayMetrics.density).toInt()
        val statusParams = statusRow.layoutParams as? android.widget.FrameLayout.LayoutParams
            ?: return
        val batteryParams = batteryRow.layoutParams as? android.widget.FrameLayout.LayoutParams
            ?: return
        if (swap) {
            statusParams.gravity = android.view.Gravity.TOP or android.view.Gravity.END
            statusParams.marginStart = 0
            statusParams.marginEnd = margin
            batteryParams.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            batteryParams.marginStart = margin
            batteryParams.marginEnd = 0
        } else {
            statusParams.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            statusParams.marginStart = margin
            statusParams.marginEnd = 0
            batteryParams.gravity = android.view.Gravity.TOP or android.view.Gravity.END
            batteryParams.marginStart = 0
            batteryParams.marginEnd = margin
        }
        statusRow.layoutParams = statusParams
        batteryRow.layoutParams = batteryParams
    }

    private data class BatteryStatus(val percent: Int, val charging: Boolean)

    /**
     * EE1 runs API 19, which predates both [BatteryManager.getIntProperty]
     * (API 21) and [BatteryManager.isCharging] (API 23). Use the sticky
     * ACTION_BATTERY_CHANGED broadcast — works on every Android version.
     */
    private fun readBatteryStatus(): BatteryStatus? {
        return try {
            if (Build.VERSION.SDK_INT >= 23) {
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                    ?: return readBatteryFromSticky()
                val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                if (pct !in 0..100) return readBatteryFromSticky()
                BatteryStatus(pct, bm.isCharging)
            } else {
                readBatteryFromSticky()
            }
        } catch (_: Exception) {
            readBatteryFromSticky()
        }
    }

    private fun readBatteryFromSticky(): BatteryStatus? {
        return try {
            val bi = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: return null
            val level = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level < 0) return null
            val pct = (level * 100 / scale).coerceIn(0, 100)
            val plugged = bi.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            BatteryStatus(pct, plugged != 0)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * [android.net.ConnectivityManager.activeNetwork] + NetworkCapabilities
     * are API 23+. On EE1 fall back to the deprecated activeNetworkInfo.
     */
    @Suppress("DEPRECATION")
    /** See EE2 copy for the design note. */
    private fun isWifiConnected(): Boolean {
        return try {
            val wifi = context.getSystemService(Context.WIFI_SERVICE)
                as? android.net.wifi.WifiManager ?: return false
            @Suppress("DEPRECATION")
            val info = wifi.connectionInfo ?: return false
            @Suppress("DEPRECATION")
            val ip = info.ipAddress
            @Suppress("DEPRECATION")
            val ssid = info.ssid.orEmpty().trim('"')
            ip != 0 && ssid.isNotEmpty() && ssid != "<unknown ssid>"
        } catch (_: Exception) {
            false
        }
    }

    private fun isPhoneConnected(): Boolean {
        return com.glasshole.glassxe.BluetoothListenerService.instance?.isPhoneConnected == true
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

    fun refreshNotificationCard() {
        val idx = cards.indexOf(CardType.NOTIFICATION)
        if (idx >= 0) notifyItemChanged(idx, PAYLOAD_NOTIF)
    }

    private fun bindNotification(holder: CardHolder) {
        val latest = NotificationStore.latest()
        val total = NotificationStore.count()
        val front = holder.itemView.findViewById<View>(R.id.notifFrontCard)
        val empty = holder.itemView.findViewById<TextView>(R.id.notifEmpty)
        val content = holder.itemView.findViewById<View>(R.id.notifContent)
        val picture = holder.itemView.findViewById<ImageView>(R.id.notifPicture)
        val gradient = holder.itemView.findViewById<View>(R.id.notifPictureGradient)
        val stack1 = holder.itemView.findViewById<View>(R.id.notifStack1)
        val stack2 = holder.itemView.findViewById<View>(R.id.notifStack2)
        val stackCount = holder.itemView.findViewById<TextView>(R.id.notifStackCount)
        if (latest == null) {
            // Strip the card chrome — empty state is just centered text
            // on bare black, mirroring the media card's "Nothing playing".
            front?.background = null
            content?.visibility = View.GONE
            empty?.visibility = View.VISIBLE
            picture?.visibility = View.GONE
            gradient?.visibility = View.GONE
            stack1?.visibility = View.GONE
            stack2?.visibility = View.GONE
            stackCount?.visibility = View.GONE
            return
        }
        // Restore the rounded card chrome — view holders are recycled, so
        // we have to re-set the drawable each time we have notifications.
        front?.setBackgroundResource(R.drawable.notif_front_card)
        content?.visibility = View.VISIBLE
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
            topSpacer?.visibility = View.VISIBLE
            middleSpacer?.visibility = View.GONE
        } else {
            picture?.setImageDrawable(null)
            picture?.visibility = View.GONE
            gradient?.visibility = View.GONE
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

        val titleIcon = holder.itemView.findViewById<ImageView>(R.id.notifTitleIcon)
        if (titleIcon != null) {
            if (latest.titleIconBitmap != null) {
                titleIcon.setImageBitmap(latest.titleIconBitmap)
                titleIcon.visibility = View.VISIBLE
            } else {
                titleIcon.visibility = View.GONE
            }
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
