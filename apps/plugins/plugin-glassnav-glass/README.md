# GlassNav (GlassHole plugin)

A turn-by-turn navigation heads-up display for Google Glass, packaged
as a GlassHole plugin.

This module is **GPL-3.0-or-later** — see [LICENSE](LICENSE). The rest
of the GlassHole project is MIT-licensed; this plugin's copyleft is
isolated to this directory and its compiled APK so the two licenses
don't intermingle.

## Credits

Designed and conceived as a re-implementation of
**[GlassNav](https://github.com/CatotheCat11/GlassNav)** by CatotheCat11
(GPL-3.0-or-later). No source files from the original repository are
imported verbatim — the UI flow, gesture vocabulary, and concept of
streaming phone GPS to Glass over Bluetooth are credited as
inspiration. See [AUTHORS](AUTHORS) for the full attribution.

If you want offline vector-tile maps and the rest of the original
GlassNav feature set, install the upstream [GlassNav release][gn] —
it can run side-by-side with this plugin on the same headset.

[gn]: https://github.com/CatotheCat11/GlassNav

## What this plugin does

When the phone is navigating in Google Maps (any destination, any mode
of transport), GlassHole's existing phone-side `NavPlugin` already
scrapes the Maps notification for turn instructions / distance / ETA
and ships them to the launcher's Home card. This plugin **opens a
dedicated full-screen nav surface on glass** that reads the same data
stream and adds:

- A larger turn-arrow + maneuver text suited for at-a-glance reading
  while moving.
- A **live speedometer** corner overlay computed from phone GPS samples
  (works without glass-side GPS, which is unreliable on EE1/XE).
- A **share-from-Maps** affordance on the phone: pick "Share" in
  Google Maps → "GlassHole — Navigate on Glass" → the phone fires the
  navigation intent and the glass plugin opens automatically.

## What it does NOT do

- No offline map rendering. There's no VTM, no Mapsforge, no tile cache
  on disk. If you want that, run upstream GlassNav.
- No on-glass destination search. Pick the destination on the phone.

## Settings (phone)

- **Units** — imperial (mph) or metric (km/h).
- **Speedometer mode** — only-during-nav, or always-on while the plugin
  is open.
- **Voice prompts** — TTS reads each new maneuver instruction aloud.
- **Speedometer position** — top-left or top-right of the HUD.
