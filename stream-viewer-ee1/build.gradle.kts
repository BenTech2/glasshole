plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.glasshole.streamplayer.ee1"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.glasshole.streamplayer.ee1"
        minSdk = 19
        targetSdk = 19
        versionCode = 3
        versionName = "0.3.0-alpha"
        multiDexEnabled = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":glass-plugin-sdk"))
    // Multidex for API 19
    implementation("androidx.multidex:multidex:2.0.1")

    // Core library desugaring for API 19
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    // NewPipeExtractor - extracts direct stream URLs from YouTube
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.25.2")

    // AndroidX
    implementation("androidx.core:core-ktx:1.10.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.1")

    // ZXing for QR scanning (works on API 19, no CameraX needed)
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.2")

    // Legacy ExoPlayer (media3 requires API 21)
    implementation("com.google.android.exoplayer:exoplayer-core:2.19.1")
    implementation("com.google.android.exoplayer:exoplayer-hls:2.19.1")
    implementation("com.google.android.exoplayer:exoplayer-ui:2.19.1")

    // Modern TLS provider for API 19 (system SSL is too old for modern servers)
    implementation("org.conscrypt:conscrypt-android:2.5.2")

    // OkHttp for Twitch API calls
    implementation("com.squareup.okhttp3:okhttp:3.12.13")

    // JSON parsing
    implementation("org.json:json:20231013")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
