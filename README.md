# GlassHole

**Give your dead Google Glass a new phone to talk to.**

GlassHole is a companion app + plugin system that lets a modern Android
phone pair with a Google Glass (Explorer, Enterprise Edition 1, or
Enterprise Edition 2) over Bluetooth and get most of the useful stuff
back that Glass lost when Google killed the MyGlass app:

- Forward phone notifications to the glass display as Glass-style cards
- Take notes on-device with voice dictation and sync them to a phone-side notebook
- Do voice calculations on glass and keep a running history on the phone
- Share a YouTube or Twitch URL from the phone's share sheet and have it auto-play on glass
- Browse glass photos and videos from the phone, download full-res, save to the phone's Photos, or delete remotely
- Adjust glass brightness, volume, screen timeout, and wake from the phone
- Install and uninstall glass APKs over Bluetooth from the phone-side APK manager
- A debug console for sending test notifications and watching the BT pipe

This is an alpha. It mostly works but it's been built and tested against
a very specific hardware matrix (a Pixel 10 Pro Fold, a Glass 2 OEM EE1
running Android 4.4, and a Google Glass Enterprise Edition 2). Your mileage
will vary.

---

## ⚠️ Disclaimer — please read

> **This entire project was written by an AI (Claude Code) acting as a
> pair programmer under my direction.** Every kotlin file, every
> manifest, every layout, every line in this README. I reviewed and
> directed the work, but the code is not human-authored and has not been
> audited line-by-line by a human expert.
>
> **No warranty. No liability.** The software is provided "as is",
> without warranty of any kind, express or implied. In no event shall
> the authors or contributors be liable for any claim, damages, or
> other liability, including but not limited to:
>
> - Bricked Glass hardware
> - Lost photos, notes, calendar events, or irreplaceable data
> - BT pairing fights with your other devices
> - Unexpected battery drain or overheating of either the phone or glass
> - Your partner getting mad at you for spending the whole weekend
>   sideloading plugins onto a ten-year-old wearable instead of doing
>   whatever it was you promised to do
> - Failed relationships, missed anniversaries, forgotten birthdays, or
>   the creeping realization that Glass was never going to come back
>
> By installing this software you are acknowledging that you are
> sideloading an alpha build of experimental AI-written code onto
> consumer hardware that the original manufacturer discontinued years
> ago. You are on your own. Please enjoy responsibly.

---

## What hardware is supported?

| Device                             | Status    | Notes |
|------------------------------------|-----------|-------|
| Google Glass Enterprise Edition 2  | ✅ Tested | Android 8.1, main development target |
| Google Glass Explorer Edition ("XE") | ⚠️ Untested on real hardware | Code paths exist, uses GDK timeline cards |
| Google Glass Enterprise Edition 1  | ✅ Tested | Android 4.4-era base, Glass 2 OEM hardware. GDK timeline calls fall back to a styled activity card when the GDK jar isn't present. |
| Android phone (Android 11+)        | ✅ Tested | Pixel 10 Pro Fold (Android 14+). Any modern Android with Bluetooth should work. |
| Android phone (Android 8–10)       | ❓ Unknown | `minSdk` is 24 (Android 7); probably fine, not tested |

If you have a real Glass XE or EE1, please file an issue with logs so I
can improve support.

---

## Overview — how it fits together

```
┌──────────────────────────┐           ┌──────────────────────────┐
│        Phone             │           │         Glass            │
│  ┌────────────────────┐  │           │  ┌────────────────────┐  │
│  │  GlassHole Phone   │  │  BT       │  │ GlassHole Base App │  │
│  │  - MainActivity    │◄─┼───RFCOMM──┼─►│ - BT listener      │  │
│  │  - BridgeService   │  │  socket   │  │ - Plugin dispatch  │  │
│  │  - PluginHost      │  │           │  │ - Notification UI  │  │
│  │  - Notification    │  │           │  └─────────┬──────────┘  │
│  │    Listener        │  │           │            │ AIDL /      │
│  └─────────┬──────────┘  │           │            │ broadcast   │
│            │             │           │            ▼             │
│  ┌─────────▼──────────┐  │           │  ┌────────────────────┐  │
│  │  Built-in plugins  │  │           │  │  Glass plugin APKs │  │
│  │  - notes           │  │           │  │  - notes-glass     │  │
│  │  - calc            │  │           │  │  - calc-glass      │  │
│  │  - stream          │  │           │  │  - stream-player   │  │
│  │  - device          │  │           │  │  - device-glass    │  │
│  │  - gallery         │  │           │  │  - gallery-glass   │  │
│  └────────────────────┘  │           │  └────────────────────┘  │
└──────────────────────────┘           └──────────────────────────┘
```

Everything between the two devices goes over one Bluetooth RFCOMM socket
using a newline-delimited text protocol. The phone side runs a single
foreground service (`BridgeService`) that owns the socket and routes
plugin messages. Each feature is a plugin — matched pair of one
in-process class on the phone side and one standalone `.apk` on the
glass side. Plugins send JSON payloads wrapped in a `PLUGIN:id:type:json`
envelope.

---

## What's in each release asset

From the [Releases page](../../releases), you'll find a zip per glass
version plus one phone APK. Install the phone APK once, then install
**one** of the glass bundles (matching your hardware).

```
glasshole-phone.apk                    (install on your Android phone)

glasshole-glass-ee1-v0.1.0-alpha.zip   (for Glass Enterprise Edition 1 / Android 4.4-era glass)
  ├── glasshole-base-ee1.apk           (base app — BT listener, plugin host, notification card UI)
  ├── glasshole-stream-player-ee1.apk  (core — Stream Player: URL receiver + video playback, also the stream plugin)
  ├── plugin-notes.apk                 (notes dictation + timeline/card)
  ├── plugin-calc.apk                  (voice calculator)
  ├── plugin-device.apk                (brightness / volume / timeout / wake control target)
  └── plugin-gallery.apk               (media scanner + chunked file transfer)

glasshole-glass-ee2-v0.1.0-alpha.zip   (for Glass Enterprise Edition 2 — Android 8.1)
  ├── glasshole-base-ee2.apk
  ├── glasshole-stream-player-ee2.apk
  ├── plugin-camera2.apk               (EE2-only — Camera2-based replacement camera)
  ├── plugin-gallery2.apk              (EE2-only — Cover Flow gallery)
  └── (same five common plugins)

glasshole-glass-xe-v0.1.0-alpha.zip    (for real Google Glass Explorer Edition)
  ├── glasshole-base-xe.apk
  ├── glasshole-stream-player-xe.apk
  └── (same five common plugins)
```

The five common plugins are identical across glass variants — they
target `minSdk 19` and work on every supported device. The base app and
the stream viewer are per-variant because each Glass generation has
different system-level APIs.

---

## Installation

### 1. Install the phone app

On your Android phone:

1. Download **`glasshole-phone.apk`** from the release page.
2. Open the APK — Android will prompt you to allow "Install unknown
   apps" from whichever browser / file manager you used. Grant that
   permission (you can revoke it after the install).
3. Tap Install.
4. Open the **GlassHole** app from your launcher.
5. The first time it runs it will ask for:
    - Bluetooth connect + scan
    - Post notifications
    - (Optional) Fine location — Android links this to BT on older versions
   Grant all of them. If you decline Bluetooth, nothing will work.

### 2. Enable notification access (so it can forward notifications)

On the main GlassHole screen tap **Enable Notification Access**. This
opens the system settings page for notification listeners. Find
**GlassHole** in the list and toggle it on. Android will warn you about
privacy — GlassHole does need to see every notification to be able to
forward the ones you pick.

Then tap **Choose Notification Apps** and pick which apps you actually
want on the glass. By default, nothing is forwarded until you opt an
app in. The picker shows only user-visible apps (YouTube, Messages,
Gmail, WhatsApp, Slack, etc.); flip the **Show system apps** switch if
you need to add something obscure.

### 3. Install the glass apps

Pick the zip for your glass variant, unpack it, and install the APKs.
**The base app MUST be installed first**, because the plugins bind to
it at startup.

#### Option A: Sideload with `adb` (recommended)

Plug the glass into your computer (or enable wireless ADB), then:

```bash
# For EE2:
adb install glasshole-base-ee2.apk
adb install glasshole-stream-player-ee2.apk
adb install plugin-notes.apk
adb install plugin-calc.apk
adb install plugin-device.apk
adb install plugin-gallery.apk
adb install plugin-camera2.apk
adb install plugin-gallery2.apk
```

Swap the filenames for `base-ee1.apk` + `stream-player-ee1.apk` or
`base-xe.apk` + `stream-player-xe.apk` if you're on EE1 or XE (and
drop the EE2-only camera2/gallery2 lines).

#### Option B: Use the phone-side APK Manager

Once the **base app** is on the glass and Bluetooth is paired to the
phone, you can push the plugins through GlassHole's APK Manager:

1. Install the base app via `adb` as in Option A.
2. Open GlassHole on the phone, connect to the glass (see "Pairing &
   connecting" below).
3. Tap **APK Manager**, then **Install APK**, and pick
   `plugin-notes.apk` from your phone's storage.
4. Wait for the chunked transfer to finish (~30s per plugin over BT).
   The glass will show the Android installer confirmation dialog; tap
   to confirm.
5. Repeat for each plugin.

Note: APK Manager install requires the **glass** to have "Unknown
sources" enabled. On EE2 that's automatic via the `REQUEST_INSTALL_PACKAGES`
permission. On EE1 / XE you may need to flip the debug toggle in the
Glass Settings card first — otherwise the Android installer dialog
never appears.

---

## Bluetooth pairing & connecting

1. On the glass, pair it with the phone the normal way (Settings → Bluetooth → new device).
2. On the phone, open **GlassHole**.
3. Pick your glass device in the **Glass Device** spinner.
4. Tap **Connect**.
5. Within a second or two the status should turn green: "Connected to
   Glass". The Glass info card will fill in with battery, model, and
   Android version.

Auto-reconnect is on by default: if the phone loses BT to the glass, it
retries with exponential backoff (1s → 4s max). Reconnect happens
within ~10s of the glass coming back into range.

**If connect fails with `read failed, socket might closed or timeout`**:
Unpair and repair the glass on the phone. Android caches the RFCOMM
service record per-device and the cache goes stale after firmware
changes or device swaps. The phone-side connect code also has a
reflection-based channel-1 fallback for when SDP is hosed.

---

## Using each feature

### Notes

**From glass**: launch the **GlassHole Notes** card. You get a two-item
menu: **New Note** and **View Notes**.

- **New Note** opens the Glass voice recognizer. Speak the note and it's
  sent to the phone and saved into a SQLite database in the GlassHole
  phone app.
- **View Notes** pulls the list of notes from the phone, shows them on
  a selectable carousel. Swipe left/right to move through the list, tap
  to open.
- In an open note: swipe horizontally on the touchpad to scroll
  content, swipe down to close. On a real touchscreen glass you can
  also vertical-drag to scroll.

**From phone**: open **Open Notes** from the main screen. Standard list
view, tap to edit, long-press to delete, FAB to create. Edits sync
back because it's the same database either side.

### Calc

Launch **GlassHole Calc** on glass. It immediately opens the voice
recognizer. Say something like *"five hundred divided by twenty-five"*
or *"twelve plus eight times three"*. The expression gets parsed,
evaluated, displayed on the glass, and the result + expression + timestamp
are forwarded to the phone, which stores them in a SharedPreferences
history. Open **Open Calculator History** on the phone to see the log
and clear it.

### Stream — YouTube and Twitch to glass

From any app on the phone that supports sharing text (YouTube app,
Twitch app, Chrome, etc.), tap share and pick **Send to Glass** /
**GlassHole**. The phone extracts the URL from the share text, forwards
it over BT to the glass stream plugin, wakes the glass display, and
launches the Stream Player with the URL pre-resolved.

Stream Player uses NewPipeExtractor to resolve YouTube videos (both
live streams and VODs) into direct HLS/MP4 URLs that play in ExoPlayer.
Twitch resolves via the Twitch GraphQL playback token endpoint into an
HLS URL.

Stream Player is bundled inside each glass release zip
(`glasshole-stream-player-<variant>.apk`) and ships as a core component
— install it alongside the base app. If it's missing, the stream
plugin logs "no Stream Player installed" and nothing launches.

### Gallery

Open **Glass Gallery** on the phone. It pulls the list of photos and
videos from the glass (scans `/sdcard/DCIM/**`, `/sdcard/Pictures/`,
and `/sdcard/Movies/`) and shows a thumbnail grid. Thumbnails come
along with the list in the initial response so they're fast (a
120×120 JPEG per item at 70% quality, base64-inlined).

- **Tap** a thumbnail to download the full-res file over BT and open
  it in the phone's default viewer (Photos/Gallery for images, the
  system video player for videos). Chunked transfer at 48 KB per
  message; expect roughly 20–40 seconds per MB.
- **Long-press** a thumbnail for a menu:
    - **Open** (same as tap)
    - **Save to phone** — copies the downloaded file into
      `Pictures/GlassHole` or `Movies/GlassHole` via MediaStore so it
      shows up in Google Photos and survives the app's cache.
    - **Delete** — removes the file from the glass after a confirmation
      dialog.

The phone caches downloaded files in its internal cache directory so
you don't pay the BT tax twice for the same file.

### Glass Settings (device controls)

Open **Glass Settings** on the phone. Shows:

- Connection status + glass battery + current glass time
- **Brightness slider** (0–255) + auto-brightness toggle
- **Volume slider** (0–max)
- **Screen timeout** slider with discrete steps
- **Wake Glass** button — forces the display on for 5 seconds
- **Sync Time Now** — tells glass to try to set its system time to the
  phone's current time
- **Refresh** — re-pulls state from glass

**Time sync caveat**: setting the system clock needs `SET_TIME`
permission, which is `signature|system` on Android. On a non-rooted
glass this will either fail or fall back to toggling Android's built-in
auto-time (which requires the glass to have a working network
connection for NTP). Best-effort feature.

Auto-sync fires every time BT reconnects to the glass; manual sync is
for debugging.

### APK Manager

Install / uninstall glass APKs over Bluetooth. Lists packages on the
glass (tap **Refresh**), lets you tap **Install APK** to push a local
APK with a progress dialog, and each row has a trash icon for
uninstall. "GlassHole" badges mark our plugin packages; "Show system
apps" toggles whether Android system packages are visible.

Install requires the glass to allow side-loading (see notes on each
glass variant). If the installer dialog never appears, check the yellow
banner that pops up — it'll tell you to enable Unknown Sources on the
headset.

### Debug console

Bottom button on the main screen. Shows:

- Bridge service connection state
- **Send Test Notification** — sends a crafted `MSG:` through the same
  pipe the real notification forwarder uses. Useful for verifying the
  card layout, the icon/app-name rendering, and the BT path without
  waiting for a real notification to land.

The main log panel on the home screen shows live events from every
plugin and the BT pipe:

- `[BT] → plugin:TYPE (N B) sent` — outbound plugin message
- `[BT] ← plugin:TYPE (N B)` — inbound
- `[Share] Extracted URL: ... (youtube)` — stream share pipeline
- `[Gallery] Download complete: name.jpg (1024 KB)` — gallery transfer
- `[PluginHost] Built-in plugin ready: notes` — startup

---

## Known limitations

- **BT throughput is the hard ceiling.** Real-world RFCOMM on these
  devices runs at roughly 100–300 kbps. A 2 MB photo takes ~1 minute,
  a 5 MB video takes ~5 minutes. Plan accordingly; this is not a
  streaming medium.
- **No end-to-end encryption on the BT channel.** Messages are plain
  text over a paired RFCOMM link. Anyone who can MITM your Bluetooth
  can read your notifications. This hasn't been a practical concern
  for Bluetooth since BLE 4.2 with secure connections, but it's worth
  calling out.
- **Notification icons are re-rendered from the source app.** They're
  cached per-package on the phone so each app's icon is only encoded
  once per phone-app session, but on first delivery from a new app
  there's a slight delay for icon encoding.
- **EE1 GDK fallback.** The EE1 I tested against doesn't ship the GDK
  `com.google.android.glass.jar`, so the timeline-card integration
  silently falls back to a styled full-screen activity that mimics the
  Glass card aesthetic. On a real Google Glass Explorer Edition that
  card should slot into the timeline properly via the GDK, but I
  haven't been able to verify that end-to-end on a genuine XE unit.
- **KDE Connect on EE2 crashes in a loop.** Unrelated to GlassHole but
  if you have KDE Connect installed on EE2 it will crash every time its
  LiveCardService tries to initialize (the GDK class exists as a stub
  and throws `RuntimeException: Stub!`). The crash dialog blocks the
  glass display. Workaround:
  `adb shell pm disable-user com.cato.kdeconnect`
- **APK Manager uninstall on EE1 requires side-loading enabled on the
  headset.** On EE2 it's automatic because `REQUEST_INSTALL_PACKAGES` is
  granted at install time; on older glass the Unknown Sources toggle
  must be on.
- **Time sync doesn't actually set the clock on non-rooted glass.**
  See "Glass Settings" above.

---

## Troubleshooting

**Notifications don't appear on glass**

1. Is the phone app showing "Connected to Glass" (green)? If not, the
   BT link is down. Tap Disconnect then Connect.
2. Is notification access granted? Settings → Notifications →
   Notification access → GlassHole = ON.
3. Have you added the source app to the **Choose Notification Apps**
   list? Default is opt-in — nothing forwards until you pick apps.
4. Check the log panel on the home screen for `[Notif]` lines when a
   notification fires on the phone.

**Gallery shows "No files on glass"**

- Storage permission may not be granted to the gallery plugin. On EE1,
  permission is install-time (should Just Work). On EE2, the gallery
  plugin has a launcher tile called **Gallery Access** — tap it once
  on the glass to approve the dialog.
- Make sure the glass actually has media in `/sdcard/Pictures`,
  `/sdcard/Movies`, or `/sdcard/DCIM/**`. Use `adb shell ls` to verify.

**Plugin messages are being dropped (unrouted plugin message)**

Happens right after you reinstall a plugin APK. The base app should
auto-rebind within ~750ms via the `PACKAGE_REPLACED` receiver. If it
doesn't, `adb shell am force-stop com.glasshole.glassee2` and relaunch
the base app to trigger discovery.

**Glass apps crash with "Stub!" from `com.google.android.glass.*`**

Same as KDE Connect — it's a GDK stub issue on EE2 where some classes
compile against the jar but throw at runtime. Our code uses reflection
with try/catch so GlassHole itself degrades gracefully, but third-party
GDK-based apps will crash. Not fixable from our side.

**Scrolling through a long note doesn't work on glass**

Should work on EE1 and EE2. If it doesn't:
- Check that you're swiping **horizontally** on the touchpad (glass
  convention: forward swipe advances content). Vertical swipe is
  reserved for the system back gesture.
- Swipe down exits the note.

**Stream share from YouTube does nothing**

- Make sure the share sheet picked **GlassHole / Send to Glass**, not
  a different target.
- Check the log panel for `[Share]` entries — should show the extracted
  URL, then `URL forwarded to glass: ...`, then on the glass side the
  Stream Player should launch.
- If there's no Stream Player installed on the glass, the stream plugin
  logs "No Stream Player app installed" and nothing launches.

---

## Building from source

### Requirements

- JDK 17
- Android Studio Iguana or later (or just the Gradle wrapper + android SDK)
- Android SDK with platforms 19, 27, and 34 installed

### Clone and build

```bash
git clone https://github.com/<user>/glasshole.git
cd glasshole

# Debug APKs for everything
./gradlew assembleDebug

# Or a specific module
./gradlew :phone:installDebug
./gradlew :glass-ee2:installDebug
./gradlew :plugin-notes-glass:installDebug
```

### Module layout

```
apps/
  phone/                    Phone companion app — main UI, BT bridge, plugin host, all built-in plugins
  core/                     "Core" installable pieces (Core-badged in the phone APK Manager,
                            not shown in the on-glass plugin carousel):
    glass-ee1/              Base app — Glass Enterprise Edition 1 / Android 4.4
    glass-ee2/              Base app — Glass Enterprise Edition 2 / Android 8.1
    glass-xe/               Base app — original Google Glass Explorer Edition
    stream-player-ee1/      Stream Player + stream plugin — EE1 (ExoPlayer + NewPipe, minSdk 19)
    stream-player-ee2/      Stream Player + stream plugin — EE2 (media3 + NewPipe, minSdk 26)
    stream-player-xe/       Stream Player + stream plugin — XE (ExoPlayer + NewPipe, minSdk 19)
    plugin-device-glass/    Device control plugin — brightness/volume/timeout/wake/time
    plugin-gallery-glass/   Photo-sync plugin — media scanner + chunked file transfer
  plugins/                  User-launchable plugins (shown in the on-glass plugin carousel):
    plugin-notes-glass/     Notes — dictation UI, note list viewer, timeline card inserter
    plugin-calc-glass/      Calculator — voice input, expression parser
    plugin-gallery2-glass/  EE2-only — Cover Flow gallery (minSdk 27)
    plugin-camera2-glass/   EE2-only — Camera2 replacement camera (minSdk 27)

sdk/
  glass-plugin-sdk/         Shared AIDL + base classes for glass plugin APKs
  plugin-sdk/               Shared AIDL + base classes for phone plugin APKs (mostly unused now, left for compat)
```

Gradle project IDs match the folder basenames — e.g. `:phone`, `:glass-ee2`,
`:plugin-notes-glass`, `:stream-player-ee1`.

Built-in phone plugins live in
`apps/phone/src/main/java/com/glasshole/phone/plugins/` and implement the
`PhonePlugin` interface in
`apps/phone/src/main/java/com/glasshole/phone/plugin/PhonePlugin.kt`.

### Adding a new plugin

1. Make a new `plugin-<name>-glass/` module that extends `GlassPluginService`
2. Add a matching phone class that implements `PhonePlugin`
3. Register it in `PluginHostService.builtInPlugins`
4. Add an activity + manifest entry on the phone side if you need UI
5. Send plugin messages via `sendToPhone(GlassPluginMessage(type, payload))`
   on the glass side and `sender(PluginMessage(type, payload))` on the phone side

---

## License

MIT. See `LICENSE`. TL;DR: do whatever you want with this, don't sue me
if it breaks.

---

## Not officially affiliated with anything

GlassHole is a personal project, not endorsed by Google or affiliated
with any Glass-related product or company. "Google Glass" is a
trademark of Google LLC. This project uses the public Google Glass
Development Kit API where it's present, via reflection, for timeline
card compatibility.
