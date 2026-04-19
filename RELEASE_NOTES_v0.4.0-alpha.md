# GlassHole v0.4.0-alpha

Three brand-new plugins, Stream Player now handles YouTube Music and
full YouTube playlists, a configurable notification timeout, and a
bunch of quality-of-life work across EE1 / EE2 / XE.

## New plugins

### OpenClaw — Telegram bot kickstarter
- Tap the **OpenClaw** tile on the glass to fire a one-shot Telegram
  message from your configured bot. Use it to kickstart a chat with
  [OpenClaw](https://openclaw.ai) (or any bot) so you can voice-reply
  from your phone's Telegram app.
- Phone settings: bot token (password-masked), chat ID
  (password-masked), custom kickstart text. **Send test message**
  button to verify credentials before leaving the settings screen.
- Runs on EE1, EE2, and XE.

### Chat — Twitch + YouTube Live chat viewer
- View chat for one Twitch channel **or** one YouTube Live stream at a
  time, scrolling on the glass.
- Twitch is anonymous (no login) over IRC/TLS.
- YouTube uses your own Data API v3 key — free tier covers a full
  session easily. Paste a live video URL or just the 11-character ID.
- **Twitch usernames** are coloured to match each user's Twitch chat
  colour; YouTube uses a stable pseudo-colour per channel.
- Phone settings: platform selector, channel / API key / URL, font
  size (10–24 sp), chat cache (50–1000 messages).
- Glass gestures (tuned for the touchpad):
  - Swipe forward / back → scroll forward / back through chat
  - Tap → jump to latest
  - Swipe down → close
- "Stick-to-bottom" behaviour: while you're scrolled up reading, new
  messages don't yank you to the bottom. Tap to catch up.
- Runs on EE1, EE2, and XE.

### Broadcast — RTMP streamer (EE2 full, EE1 / XE legacy)
- Stream the glass camera to any RTMP endpoint (Twitch, YouTube Live,
  OBS, a local nginx-rtmp, …). Full URL with stream key, password-
  masked.
- **Four display modes** while streaming:
  - Show viewfinder
  - Hide preview (screen stays lit)
  - Screen off after 3 seconds (tap to bring it back)
  - **Show chat instead of viewfinder** — uses Broadcast's own
    Twitch / YouTube settings, independent of the Chat plugin; RTMP
    feed is unaffected
- Resolution / FPS / bitrate / mic-audio toggles.
- **EE2 variant** (Camera2): defaults to 1280×720 @ 30 fps, 1500 kbps.
- **EE1 / XE variant** (Camera1, packaged in those release zips):
  defaults to 854×480 @ 24 fps, 800 kbps to fit the older SoCs. Drop
  to 640×360 if you see stutter.

## Stream Player
- **YouTube Music** — music.youtube.com URLs now play, with album art
  on screen and audio-only encoding so the player isn't hauling video
  frames for a track that has none.
- **YouTube playlists** — share a playlist URL (or any watch?v=…&list=
  URL) and the player walks the queue track-by-track. Swipe forward /
  back on the touchpad (or tap left / right on EE2) to jump; auto-
  advances when a track ends.
- **"Track N of M · Title"** overlay at the bottom for a few seconds
  after each track transition.
- Ported to EE1 and XE (previously EE2-only). Same native YouTube
  resolver via NewPipeExtractor.

## Notifications
- **Configurable on-screen timeout** in the phone's Glass Settings:
  3 s / 5 s / 8 s / 12 s / 15 s / 20 s / 30 s / 60 s. Value rides
  along with every forwarded notification, so there's no sync step —
  change it on the phone, next notification uses the new timeout.

## Phone-app polish
- Plugin-row settings icon is now a proper **gear** (was Android's
  default wrench, which was inconsistent across themes).
- New **Plugins ▸ Settings** screens for OpenClaw, Chat, and
  Broadcast — open them from the gear icon next to the plugin row.
- OpenClaw chat ID is password-masked to match the bot token.
- Broadcast has its **own** chat-overlay configuration — completely
  decoupled from the Chat plugin's settings, so you can (for example)
  watch your Twitch on the Chat plugin and overlay a different
  channel's chat on the Broadcast feed.

## Gesture fixes (EE1 / XE touchpad)
- The Chat viewer, Stream Player, and Broadcast activities now handle
  **both** SOURCE_TOUCHPAD events (EE1 / XE) **and** screen-surface
  events (EE2) through a single shared gesture path, so scrolling and
  next/prev work the same on all three variants.
- Tap-to-pause on Stream Player now works on EE1 / XE without relying
  on the GDK gesture detector's translated KEYCODE_DPAD_CENTER.

## Install

Each glass zip (EE1 / EE2 / XE) is a drop-in set:

```
adb install -r glasshole-base-<variant>.apk
adb install -r glasshole-stream-player-<variant>.apk
adb install -r plugin-device.apk
adb install -r plugin-gallery.apk
adb install -r plugin-notes.apk
adb install -r plugin-calc.apk
adb install -r plugin-openclaw.apk
adb install -r plugin-chat.apk
adb install -r plugin-broadcast.apk     # Camera2 on EE2, Camera1 on EE1 / XE
```

Phone: install `glasshole-phone.apk`.

## Known limitations
- **Broadcast screen-off mode** drops the window brightness near-zero
  rather than truly powering the display — true off requires a
  foreground service which pauses the Camera capture.
- **YouTube Live chat** needs your own API key; quota is per-key, so
  heavy/long sessions may eventually hit the free-tier daily cap.
- **EE1 / XE broadcast** will struggle at 720p on the older SoCs; the
  480p24 default is the sweet spot.
