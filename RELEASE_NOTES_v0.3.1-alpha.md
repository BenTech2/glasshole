# GlassHole v0.3.1 — Phone app patch

Phone-app-only patch on top of v0.3.0-alpha. Glass-side APKs are
unchanged — keep your existing glass installs.

## Fixes
- **Stop button on the GlassHole notification now actually stops the
  service.** Earlier it left the reconnect loop + wake lock running
  because activities / the plugin host were still bound to the
  service. The Stop action now releases the Bluetooth socket, releases
  the wake lock, removes the notification, and kills the GlassHole app
  process outright. Tap the GlassHole launcher icon to bring it back
  when you want to use your glass again. This should fix the
  background-battery drain when the glass isn't nearby.
- **No more crash when unfolding to the inner screen.** The
  two-pane layout for larger screens (`layout-w600dp/activity_main.xml`)
  was still on the pre-Plugins-screen button set, so the app
  NullPointerException'd on any Pixel Fold / tablet when the wider
  layout kicked in.

## Install
Download `glasshole-phone.apk` below and install — the glass zips from
v0.3.0-alpha are still the right thing to install on your glass.
