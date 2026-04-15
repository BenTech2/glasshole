# GlassHole v0.2.0-alpha — What's New

## Smarter Notifications on Glass
- Notifications now show action buttons just like Android Wear — tap **Reply**, **Open on Phone**, or **Watch on Glass** right from the card
- **Voice reply** — speak your response into the glass and it sends straight back through the original app (texts, Discord, Telegram, etc.)
- **Image previews** — MMS, Discord image messages, and doorbell camera snapshots show a thumbnail right on the notification
- **Smart "Open on Phone"** — tapping it on a YouTube notification jumps straight to that video on your phone; works with any link in a notification
- Clutter removed — "Mark as read" and "Dismiss" buttons no longer crowd the card (a swipe down already dismisses anyway)

## Cleaner Phone App
- New **Plugins** screen shows every GlassHole plugin with its version and a direct Open button
- Main screen decluttered — only essentials stay visible
- **Remembers your Glass** — auto-reconnects on app launch, no more hunting through the Bluetooth picker
- New **Glass Settings** toggles for auto-start and tilt-to-wake

## Glass Starts Automatically
- After a reboot or a GlassHole update, the app now comes back on its own — no more manually opening the tile to reconnect
- There's a toggle in Glass Settings if you'd rather it not auto-start

## EE1 Improvements
- Notification text is bigger and easier to read
- Action buttons redesigned — clean outlined look with a highlighted selection
- Touchpad navigation fixed — swipe left/right to move between buttons, tap to pick, swipe down to close

## EE2 Exclusives
- **Tilt to Wake** — look up to turn the glass display on, like XE/EE1 had natively (optional, uses a bit more battery)
- **New Camera app** — replaces the stock Glass camera, supports still capture and video recording (tap to take a photo, long-press to start/stop recording)
- **Cover Flow Gallery** — iPod-style photo browser with a smooth scaling carousel, swipe through your glass photos and videos on-device

## Housekeeping
- Plugin names cleaned up — just "Notes", "Calc", "Stream" instead of "GlassHole Notes", etc.
- Uninstall prompt now actually appears on the glass when removing plugins from the phone
- Lots of reliability fixes for notifications, replies, and connection drops

## Known limitations
- Tilt-to-wake uses extra battery — it's off by default. Turn it on from Glass Settings only when you want the gesture; expect ~10–20% additional idle drain per hour on EE2 while enabled.
- The new Camera plugin has no HDR and no flash — EE2 has no flash LED and its sensor doesn't expose a usable HDR scene mode.
- EE1 and XE still need more real-hardware testing — please file issues if anything looks off on those variants.
