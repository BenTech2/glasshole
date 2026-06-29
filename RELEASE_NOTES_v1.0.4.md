# GlassHole v1.0.4

> ⚠️ **Experimental plugins in this release.**
> Four plugins ship as **experimental** — they work end-to-end but
> have known rough edges and may have surprising behavior. File
> issues if anything's broken; expect quality improvements in 1.0.5.
>
> - **GlassNav** — turn-by-turn navigation. Route fetches require
>   data via the phone; offline tiles only after dropping `.map`
>   files into `/sdcard/glasshole/maps/`. Plugin AIDL bind can drop
>   (settings page won't load + share doesn't open the activity);
>   restart the launcher to recover.
> - **Translate** — tap-to-translate via on-device ML Kit. JP→EN
>   quality on short/vertical signage is rough (`ポイ捨て禁止` →
>   "Traveling" was a real test result). First use needs Wi-Fi on
>   the phone to fetch the OCR + translation models (~55 MB).
> - **SkyMap** — sensor-driven star / moon / planet overlay. Indoors
>   without GPS the observer location falls back to a default
>   mid-US point, so the alignment is fictional until you go
>   outside with a fix. Magnetic interference (electronics, metal
>   surfaces) makes the view drift; only ~70 brightest stars in
>   the bundled catalog.
> - **SkyTrack** — AR overhead aircraft tracker via the OpenSky
>   public ADS-B feed. Phone needs internet to fetch each poll;
>   anonymous OpenSky tier is 400 req/day (~enough for ~3 hours
>   at the default 30-second polling). Free registered tier
>   bumps that to 4000 req/day. GPS must be fresh on the phone
>   — if the status banner says "No GPS", briefly open Maps on
>   the phone to nudge the location provider. Same magnetometer
>   sensitivity as SkyMap.
> - **AI Assistant** — voice in, AI response on glass. Requires your
>   own provider API key (OpenAI / Anthropic / Gemini). Response
>   latency depends on the provider's API; failures show on glass
>   as toast text rather than a structured retry UI.

Headline change: **GlassNav** is now a fully ported, GPL-3.0-licensed
plugin based on [CatotheCat11/GlassNav](https://github.com/CatotheCat11/GlassNav).
Real VTM vector-tile rendering, Valhalla turn-by-turn navigation, head-
up map rotation, share-from-Google-Maps pipeline, and offline map
support that works without any internet on the glass.

## New plugin — GlassNav

- VTM vector map renderer (the same one upstream GlassNav uses).
- **Head-up rotation** — map turns to face whatever direction you're
  looking, on all travel modes.
- **Share-from-Maps**: open Google Maps on your phone, **Share** any
  destination → pick **Navigate on Glass** → glass opens GlassNav,
  the destination card loads, you pick walk / bike / drive (or set
  a default in plugin settings), Valhalla routes, navigation starts.
  Phone Maps no longer also starts navigating — glass-only by default.
- **Phone GPS over Bluetooth** — opening GlassNav (any path: share,
  app drawer, voice trigger) automatically requests the phone to
  start streaming GPS to the glass. Position dot follows your phone.
- **Auto-fallback to driving** when Valhalla rejects walk/cycle with
  `DistanceExceeded` on a too-long route.
- **Settings (phone-side, via Plugins → GlassNav → gear icon):**
  - Keep glass screen on while GlassNav is open
  - Distance units (miles / kilometers)
  - Auto-start when route is shared (Ask / Walking / Cycling / Driving)
  - ETA display (Arrival time / Time remaining)
  - Speedometer (Off / Only while navigating / Always)
  - Speedometer position (Top-right / Bottom-right)
- Runs on EE1, EE2, and XE.

## Adding offline maps to your glass

Online tile fetching works but is slow on Glass and burns Wi-Fi.
Offline map files make GlassNav way snappier and remove the
internet dependency for the map view (you still need Wi-Fi or phone
data for one-time Valhalla route fetches and Nominatim search).

### 1. Find a map file for your area

Browse https://download.mapsforge.org/maps/v5/ — pick your continent
→ country → state/region. The `.map` files are mapsforge **v5**
format, which is what VTM 0.27 (bundled with GlassNav) expects.

Sizes are reasonable — a whole US state is usually 200-700 MB. The
full United States is ~12 GB and won't fit on EE1's 16 GB flash, so
download per-state instead.

### 2. Push it to your glass

GlassNav scans `/sdcard/glasshole/maps/` for any `*.map` files. Drop
one or more there and it picks the best one automatically (any file
whose bounding box contains your current GPS position; smaller bbox
wins for finer detail; first file alphabetically if no GPS fix yet).

Find your glass's ADB serial first:

```
adb devices
```

Then push the map:

```
# Example — Michigan, 265 MB
curl -O https://download.mapsforge.org/maps/v5/north-america/us/michigan.map
adb -s <your-glass-serial> push michigan.map /sdcard/glasshole/maps/
```

You can drop multiple states in there — GlassNav will choose the right
one each time based on where you are. Switching regions on a road trip
needs no app interaction.

### 3. Force GlassNav to re-scan

```
adb -s <your-glass-serial> shell am force-stop com.glasshole.plugin.glassnav.glass
```

Open GlassNav again — `adb logcat | grep MainActivity` will show
`Loaded map from local file: /sdcard/glasshole/maps/michigan.map`.

If no `.map` files are present in the new directory, GlassNav still
falls back to the legacy `/sdcard/Map.map` slot for backwards compat,
then to the online OpenStreetMap US tile server if neither exists.

### Removing or replacing a map

```
adb -s <your-glass-serial> shell rm /sdcard/glasshole/maps/michigan.map
```

— then push a new one and force-stop again.

## New plugin — SkyTrack

AR overhead-aircraft tracker. Inspired by the original idea behind
[cpaczek/skylight](https://github.com/cpaczek/skylight) (MIT, design
credit) — written from scratch as a GlassHole plugin rather than a
direct source port. Look at the sky, see real flights nearby
labeled with callsign + altitude.

- **Live ADS-B from the OpenSky Network** — every poll the phone
  fetches `/api/states/all` for a bounding box centered on your
  current GPS, hands the result to the glass over BT.
- **Heading-rotated triangles** at each aircraft's observer-relative
  (azimuth, altitude), sized by distance and colored by altitude
  band — orange under ~5,000 ft, chartreuse 5–20k, pale blue above.
- **Per-aircraft labels**: callsign + altitude in thousands of feet
  (`DAL860  10k ft`). Toggleable in the plugin settings.
- **Settings**: range 20 / 50 / 100 / 200 km · min altitude 0 / 1k
  / 5k / 10k ft · poll interval 10 / 20 / 30 / 60 s · labels on/off.
- **Free tier limits**: anonymous OpenSky is 400 req/day; the 30 s
  default = 2,880 polls/day, so for sustained use create a free
  account at https://opensky-network.org (registered tier is
  4,000 req/day). Anonymous works for casual testing.
- **Same sensor pipeline as SkyMap** — uses the Glass-worn coordinate
  remap + prism-flip correction. Tap the touchpad to toggle a
  debug HUD with live sensor + feed counts.
- **Attribution**: OpenSky Network gets a card in Settings → Open-
  source licenses. cpaczek/skylight is credited there too as design
  inspiration.

## Other changes

- **About card** on the home carousel now shows the glass's Wi-Fi MAC,
  Bluetooth MAC, and current IP address alongside the version + build
  number.
- Companion + share path: phone-side `GlassNavCompanionActivity` adds
  in-app destination search (Nominatim) + send-to-glass + opt-in GPS
  streaming toggle, reachable via Plugins → GlassNav → Open.
- Vendored `maplibre-navigation-core` (KitKat-compatible fork) with a
  Kotlin shim layer that bridges to the Java maplibre-gl 6.0.1
  artifacts so it compiles against our Kotlin 1.9 toolchain.
- `glass-gdk-stubs` module providing compile-only Glass GDK class
  shapes — real implementations come from the Glass OS at runtime.

## Install

Standard release flow — install the matching launcher APK for each
of your editions, plus the phone APK and any plugin APKs you want.
The GlassNav plugin APK is ~12 MB (includes VTM native libs for
four ABIs).
