package com.glasshole.phone

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar

/**
 * Attribution page for every third-party library shipped inside any
 * GlassHole APK (phone, glass, or stream-player). Cards group by
 * project — Google's umbrella libraries (AndroidX, Material, ExoPlayer)
 * collapse into one entry each so the list stays readable, while the
 * load-bearing third-party deps each get their own card.
 *
 * Tap a card → opens the project's source URL in the system browser
 * so the reader can fetch the canonical license text. Each card lists
 * the SPDX license id, version, and the upstream copyright holder.
 *
 * IMPORTANT: when adding or removing a Maven dependency anywhere in
 * the workspace, update [LICENSES] below to match. The list is the
 * shipping attribution surface — missing entries are a real
 * compliance issue, especially for non-permissive licenses
 * (NewPipeExtractor is GPL-3.0 and must stay credited).
 */
class LicensesActivity : AppCompatActivity() {

    private data class License(
        val name: String,
        val version: String,
        val spdx: String,
        val copyright: String,
        val url: String
    )

    companion object {
        private val LICENSES: List<License> = listOf(
            License(
                "AndroidX Jetpack libraries",
                "various",
                "Apache 2.0",
                "© The Android Open Source Project",
                "https://developer.android.com/jetpack/androidx"
            ),
            License(
                "Material Components for Android",
                "1.12.0",
                "Apache 2.0",
                "© Google LLC",
                "https://github.com/material-components/material-components-android"
            ),
            License(
                "ExoPlayer (Media3)",
                "1.1.1",
                "Apache 2.0",
                "© Google LLC",
                "https://github.com/androidx/media"
            ),
            License(
                "ExoPlayer 2.x",
                "2.19.1",
                "Apache 2.0",
                "© Google LLC",
                "https://github.com/google/ExoPlayer"
            ),
            License(
                "Google Play services Location",
                "21.1.0",
                "Custom (Google API ToS)",
                "© Google LLC",
                "https://developers.google.com/android/guides/setup"
            ),
            License(
                "Google ML Kit Object Detection",
                "17.0.2",
                "Custom (Google API ToS)",
                "© Google LLC",
                "https://developers.google.com/ml-kit/vision/object-detection"
            ),
            License(
                "Kotlin Coroutines",
                "1.7.3",
                "Apache 2.0",
                "© JetBrains s.r.o.",
                "https://github.com/Kotlin/kotlinx.coroutines"
            ),
            License(
                "OkHttp",
                "4.12.0 / 3.12.13",
                "Apache 2.0",
                "© Square, Inc.",
                "https://square.github.io/okhttp/"
            ),
            License(
                "Conscrypt",
                "2.5.3",
                "Apache 2.0",
                "© The Android Open Source Project",
                "https://github.com/google/conscrypt"
            ),
            License(
                "ZXing Core",
                "3.5.2",
                "Apache 2.0",
                "© ZXing authors",
                "https://github.com/zxing/zxing"
            ),
            License(
                "ZXing Android Embedded",
                "4.3.0",
                "Apache 2.0",
                "© Journey Mobile, Inc.",
                "https://github.com/journeyapps/zxing-android-embedded"
            ),
            License(
                "NewPipeExtractor",
                "v0.25.2",
                "GPL-3.0",
                "© The NewPipe Authors",
                "https://github.com/TeamNewPipe/NewPipeExtractor"
            ),
            License(
                "RootEncoder / rtmp-rtsp-stream-client-java",
                "2.5.2 / 2.2.6",
                "Apache 2.0",
                "© pedroSG94",
                "https://github.com/pedroSG94/RootEncoder"
            ),
            License(
                "JSch (mwiede fork)",
                "0.2.16",
                "BSD-3-Clause",
                "© Atsuhiko Yamanaka, JCraft Inc., Michael Wiede",
                "https://github.com/mwiede/jsch"
            ),

            // ---- GlassNav plugin port + its dependency stack ----
            License(
                "GlassNav (upstream)",
                "1.0.1",
                "GPL-3.0-or-later",
                "© CatotheCat11",
                "https://github.com/CatotheCat11/GlassNav"
            ),
            License(
                "OpenPrism (Glass UI library)",
                "1.2.0",
                "Apache 2.0",
                "© CatotheCat11",
                "https://github.com/CatotheCat11/OpenPrism"
            ),
            License(
                "VTM (Vector Tile Map)",
                "0.27.0",
                "LGPL-3.0",
                "© Mapsforge contributors",
                "https://github.com/mapsforge/vtm"
            ),
            License(
                "Mapsforge",
                "0.27.0",
                "LGPL-3.0",
                "© Mapsforge contributors",
                "https://github.com/mapsforge/mapsforge"
            ),
            License(
                "MapLibre Navigation SDK (Kurviger fork)",
                "vendored — see apps/lib/maplibre-navigation-core",
                "MIT",
                "© Kurviger (2024), MapLibre (2021), Flitsmeister (2019)",
                "https://github.com/maplibre/maplibre-navigation-android"
            ),
            License(
                "MapLibre Android Java Utilities (geojson + turf)",
                "6.0.1",
                "Apache 2.0",
                "© MapLibre contributors",
                "https://github.com/maplibre/maplibre-java"
            ),
            License(
                "AndroidSVG",
                "1.4",
                "Apache 2.0",
                "© Paul LeBeau",
                "https://github.com/BigBadaboom/androidsvg"
            ),
            License(
                "Mapbox Vector Tile",
                "4.0.6",
                "Apache 2.0",
                "© ci-cmg / Mapbox contributors",
                "https://github.com/ci-cmg/mapbox-vector-tile-java"
            ),
            License(
                "JTS Topology Suite (Core)",
                "1.20.0",
                "EPL-2.0 + EDL-1.0",
                "© Eclipse Foundation / Vivid Solutions",
                "https://github.com/locationtech/jts"
            ),
            License(
                "Protocol Buffers — Java",
                "3.24.2",
                "BSD-3-Clause",
                "© Google LLC",
                "https://github.com/protocolbuffers/protobuf"
            ),
            License(
                "kotlinx-serialization-json",
                "1.6.3",
                "Apache 2.0",
                "© JetBrains s.r.o.",
                "https://github.com/Kotlin/kotlinx.serialization"
            ),
            License(
                "OpenStreetMap data",
                "ODbL 1.0",
                "ODbL 1.0",
                "© OpenStreetMap contributors",
                "https://www.openstreetmap.org/copyright"
            ),
            License(
                "Valhalla routing service",
                "openstreetmap.de demo",
                "Apache 2.0",
                "© Mapbox / OpenStreetMap Germany e.V.",
                "https://github.com/valhalla/valhalla"
            ),
            License(
                "Nominatim geocoder",
                "openstreetmap.org demo",
                "BSD-2-Clause / Public Domain",
                "© OpenStreetMap contributors",
                "https://nominatim.org/"
            ),
            License(
                "OpenSky Network ADS-B feed",
                "REST API v1",
                "Custom (non-commercial)",
                "© The OpenSky Network — data contributed by community ground receivers",
                "https://opensky-network.org/"
            ),
            License(
                "cpaczek/skylight (design inspiration for SkyTrack)",
                "—",
                "MIT (origin project)",
                "© Caleb Paczek — SkyTrack is a from-scratch port of the AR aircraft-tracker concept",
                "https://github.com/cpaczek/skylight"
            )
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_licenses)

        findViewById<MaterialToolbar>(R.id.toolbar)
            .setNavigationOnClickListener { finish() }

        val list = findViewById<RecyclerView>(R.id.licensesList)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = Adapter(LICENSES) { entry ->
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(entry.url)))
            } catch (_: Exception) { /* no browser installed — silent */ }
        }
    }

    private class Adapter(
        private val items: List<License>,
        private val onClick: (License) -> Unit
    ) : RecyclerView.Adapter<Adapter.Holder>() {

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.licenseName)
            val sub: TextView = view.findViewById(R.id.licenseSub)
            val copyright: TextView = view.findViewById(R.id.licenseCopyright)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_license, parent, false)
            return Holder(v)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = items[position]
            holder.name.text = entry.name
            holder.sub.text = "${entry.spdx} · ${entry.version}"
            holder.copyright.text = entry.copyright
            holder.itemView.setOnClickListener { onClick(entry) }
        }

        override fun getItemCount(): Int = items.size
    }
}
