plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.glasshole.streamplayer.ee2"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.glasshole.streamplayer.ee2"
        minSdk = 26
        targetSdk = 27
        versionCode = 3
        versionName = "0.3.0-alpha"
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
}

dependencies {
    implementation(project(":glass-plugin-sdk"))
    // Core library desugaring — NewPipeExtractor needs URLDecoder.decode(String, Charset)
    // which is Java 10+ / Android API 33+. EE2 is API 27, so we desugar.
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    // AndroidX
    implementation("androidx.core:core-ktx:1.10.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.1")

    // ExoPlayer for HLS streaming
    implementation("androidx.media3:media3-exoplayer:1.1.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.1.1")
    implementation("androidx.media3:media3-ui:1.1.1")

    // NewPipeExtractor - resolves YouTube to direct HLS/MP4 URLs
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.25.2")

    // OkHttp (used by both StreamResolver and NewPipeDownloader)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON parsing
    implementation("org.json:json:20231013")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
