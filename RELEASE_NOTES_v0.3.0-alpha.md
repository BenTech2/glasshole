# GlassHole v0.3 — What's New

## Glass app launcher
- The GlassHole app on glass is now a **plugin carousel** — swipe to
  change focused plugin, tap to launch (XE, EE1, EE2)
- EE1 / XE: **hold** a plugin icon for a details popup (version, variant,
  size, package)
- The old "tap for voice reply" on the main screen is gone (tap-to-reply
  on notifications is unchanged)
- Real icons on Notes, Calc, Camera, and Cover Flow Gallery (no more
  stock Android dialer / menu icons)

## Stream Player — more sites, same one APK
- **Rebuilt as a single app**: the old `plugin-stream.apk` is gone, its
  job is now done inside the Stream Player itself. One less APK per
  glass zip.
- Renamed from "Glass Stream Viewer" to **"Stream Player"** (also moved
  package names into the `com.glasshole.*` namespace)
- **New native extractors**: Vimeo, Dailymotion, Reddit (both `v.redd.it`
  and full post URLs)
- **Direct video URLs** — anything ending in `.mp4 / .webm / .mkv / .ts /
  .mov / .m4v` now plays as progressive
- **WebView fallback** — any URL we can't natively extract opens in a
  fullscreen WebView with HTML5 video (TikTok, Facebook, arbitrary
  sites). On modern Glass (EE2) this works for a lot of things; on
  KitKat-era glass (EE1 / XE) the built-in WebView is ancient so many
  modern sites will misbehave or refuse to load.

## XE fixes (first real-hardware testing)
- Notifications: action row now navigates correctly via touchpad — swipe
  between actions, tap to invoke
- Notes: dictation + list view now sync with the phone
- APK Manager + Plugins screen on phone work end-to-end with XE
- Share a stream from phone → Stream Player wakes + plays on XE
- Base app auto-rediscovers plugins when their APK is reinstalled (no
  reboot required)

## Phone app
- APK Manager: every GlassHole package now shows a **GlassHole** tag
  plus a **Core** or **Plugin** tag
- Plugins screen: list is now **dynamic** — reflects whatever plugins
  are actually installed on the connected glass. Core apps (device,
  stream, photo-sync) are hidden
- Plugin counter on the main screen shows the count of plugins on glass
  (not phone-side)
- Main screen: **Glass Gallery** button added between Notifications and
  Plugins
- Device Controls: **Timezone picker** — pick an IANA timezone and push
  it to the glass (non-rooted EE2 may still fail — the toggle falls
  back to `su` if available)
- Notification forwarder drops **media-playback "now playing" cards**
  (YouTube, Spotify, etc.) so opening a video on your phone doesn't
  blast the glass every time

## Device plugin (brightness / time / timeout)
- Added a **"Device Access"** launcher tile on glass. Tapping it opens
  system settings and asks for the "Modify system settings" permission
  required on Android 6+ (EE2). Without this, brightness / timeout /
  auto-time silently fail.

## Packaging
- Release APKs are now **debug-signed** (same key across all modules)
  so sideloading from the release zip no longer hits "package not
  installed" errors. If you installed v0.2 APKs and had issues, this
  fixes that.

## Known limitations
- **EE2 hold-for-details** — the EE2 touchpad doesn't deliver a
  reliable long-press event for the launcher, so hold-for-info is XE /
  EE1 only.
- **Timezone change** — `setTimeZone` is `signature|privileged` on EE2,
  so the native path is almost always blocked. The `su` fallback works
  only on rooted glass. Use your phone's Sync Time button as a
  workaround.
- **Stream Player first-boot delay** — on KitKat the Stream Player APK
  hits MultiDex extraction (~8 s) the first time the base app tries to
  bind it after a reboot. One plugin may be unreachable for a few
  seconds until it warms up; everything after that is instant.
- **WebView fallback on XE / EE1** — KitKat's system WebView is very
  old; many sites (TikTok, Facebook, any site with a strict TLS
  handshake) will misrender or refuse to load. EE2's WebView is
  Chromium 62 and handles more.

## Up next
- Better phone-side extractor path (headless WebView on phone) so
  TikTok/Facebook/etc. work on XE and EE1 without relying on
  ten-year-old Chromium
- Proper app icons on every plugin + both base apps (several still use
  stock Android drawables)
- More plugin ideas: OpenClaw chat initiator, RTMP-out streamer
