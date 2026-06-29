// SPDX-License-Identifier: Apache-2.0
// Vendored from GlassNav (https://github.com/CatotheCat11/GlassNav),
// itself a KitKat-compatible fork of maplibre/maplibre-navigation-android.
// Build script rewritten for our Gradle (8.2 / Kotlin 1.9.22) — original
// targeted AGP 9 / Kotlin 2.3.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "org.maplibre.navigation.core"
    compileSdk = 34
    defaultConfig {
        minSdk = 19
        consumerProguardFiles("consumer-rules.pro")
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    // MapLibre GeoJSON + Turf: use the Java 6.0.1 artifacts (NOT the
    // 7.0.0-pre0 Kotlin-multiplatform builds — those were compiled
    // with Kotlin 2.1 and won't load in our Kotlin 1.9.22 runtime).
    // The shim classes in src/main/java/org/maplibre/geojson/
    // {model,turf,utils} bridge nav-core's expected import paths to
    // these Java artifacts' actual package layout.
    api("org.maplibre.gl:android-sdk-geojson:6.0.1")
    api("org.maplibre.gl:android-sdk-turf:6.0.1")
    // Kotlinx versions compatible with Kotlin 1.9.22.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
}
