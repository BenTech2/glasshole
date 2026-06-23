// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Ben Harlett
// Port of GlassNav (https://github.com/CatotheCat11/GlassNav) by CatotheCat11.
// Original is GPL-3.0-or-later. This module preserves that license.
// See AUTHORS for attribution + LICENSE for terms.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

apply(from = "${rootDir}/build-versioning.gradle.kts")

android {
    // Namespace = upstream GlassNav's Java package, so R class is
    // generated at com.cato.glassnav.R and the verbatim-ported
    // source compiles without a global find-replace. applicationId
    // is distinct so the APK installs under our plugin namespace
    // and doesn't collide with the original GlassNav APK if both
    // are ever on the same device.
    namespace = "com.cato.glassnav"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.glasshole.plugin.glassnav.glass"
        minSdk = 19
        targetSdk = 34
        versionCode = (project.extra["computedVersionCode"] as Int)
        versionName = (project.extra["computedVersionName"] as String)
        multiDexEnabled = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // VTM ships per-ABI native libs (~6 MB each). For now we bundle
    // all four so any Glass edition just works.
    packaging {
        jniLibs {
            pickFirsts.add("lib/**/libvtm-jni.so")
        }
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module"
            )
        }
    }
}

// Pin kotlin-stdlib to our project Kotlin (1.9.22). OpenPrism / one of
// its transitive deps pulls in kotlin-stdlib 2.2.10 which has Kotlin-2.2
// metadata our Kotlin-1.9.22 compiler can't read — triggers an
// avalanche of unrelated "Unresolved reference" errors otherwise.
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin" &&
            requested.name.startsWith("kotlin-stdlib")) {
            useVersion("1.9.22")
            because("Pin to project Kotlin 1.9.22")
        }
    }
}

dependencies {
    // ---- Our glass plugin SDK (BT bridge, density fix, etc.) ----
    implementation(project(":glass-plugin-sdk"))
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    // ---- AndroidX ----
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.multidex:multidex:2.0.1")

    // ---- KitKat HTTPS: Conscrypt brings TLS 1.2+ to OpenSSL ----
    implementation("org.conscrypt:conscrypt-android:2.5.3")

    // ---- HTTP + IO (GlassNav's CustomTrust uses OkHttp 3.12.x) ----
    implementation("com.squareup.okhttp3:okhttp:3.12.12")
    implementation("com.squareup.okio:okio:3.6.0")

    // ---- Mapsforge: raster + offline .map file rendering ----
    implementation("com.github.mapsforge.mapsforge:mapsforge-core:0.27.0")
    implementation("com.github.mapsforge.mapsforge:mapsforge-map:0.27.0")
    implementation("com.github.mapsforge.mapsforge:mapsforge-map-reader:0.27.0")
    implementation("com.github.mapsforge.mapsforge:mapsforge-map-android:0.27.0")

    // ---- VTM: vector tile rendering ----
    implementation("com.github.mapsforge.vtm:vtm:0.27.0@jar")
    implementation("com.github.mapsforge.vtm:vtm-themes:0.27.0@jar")
    implementation("com.github.mapsforge.vtm:vtm-android:0.27.0@jar")
    implementation("com.github.mapsforge.vtm:vtm-http:0.27.0@jar")
    implementation("com.github.mapsforge.vtm:vtm-mvt:0.27.0@jar")
    runtimeOnly("com.github.mapsforge.vtm:vtm-android:0.27.0:natives-armeabi-v7a@jar")
    runtimeOnly("com.github.mapsforge.vtm:vtm-android:0.27.0:natives-arm64-v8a@jar")
    runtimeOnly("com.github.mapsforge.vtm:vtm-android:0.27.0:natives-x86@jar")
    runtimeOnly("com.github.mapsforge.vtm:vtm-android:0.27.0:natives-x86_64@jar")

    // ---- Vector tile + geometry stack (used by OsmMvtTileSource) ----
    implementation("com.caverock:androidsvg:1.4")
    implementation("com.google.protobuf:protobuf-java:3.24.2")
    implementation("io.github.ci-cmg:mapbox-vector-tile:4.0.6")
    implementation("org.locationtech.jts:jts-core:1.20.0")

    // NOTE: OpenPrism is intentionally NOT included. It bundles the
    // full GDK as Java-21 bytecode, which would force this module to
    // Java 21 source compat. AGP 8.2 + JDK 21 has a known jlink
    // incompatibility (image-transform fails), so we'd need to bump
    // AGP project-wide. Instead, we use our own :glass-gdk-stubs
    // (Java 17 compatible) for the GDK class shapes — real Glass
    // devices provide the runtime impl.
    compileOnly(project(":glass-gdk-stubs"))

    // ---- Vendored MapLibre nav-core — KitKat-compatible fork.
    //      Compiles against our Kotlin 1.9.22 via shim classes that
    //      bridge maplibre-gl 6.0.1 Java artifacts to nav-core's
    //      expected org.maplibre.geojson.{model,turf,utils} API.
    implementation(project(":maplibre-navigation-core"))
}
