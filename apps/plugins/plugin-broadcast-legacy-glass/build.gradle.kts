plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    // Different namespace from the Camera2 variant so Gradle doesn't
    // fight over R-class generation when both modules are included.
    namespace = "com.glasshole.plugin.broadcast.legacy.glass"
    compileSdk = 34

    defaultConfig {
        // Applicationid matches the Camera2 variant so the phone-side
        // plugin manager (PluginsActivity.phoneSettings) recognises
        // either APK as "broadcast". Only one is ever installed on any
        // single glass, so there's no runtime collision.
        applicationId = "com.glasshole.plugin.broadcast.legacy.glass"
        minSdk = 19
        targetSdk = 34
        versionCode = 2
        versionName = "0.4.0-alpha"
        multiDexEnabled = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    lint {
        // Targeting API 19 is intentional for EE1 / XE compatibility.
        disable += "ExpiredTargetSdkVersion"
        abortOnError = false
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation(project(":glass-plugin-sdk"))
    implementation("androidx.multidex:multidex:2.0.1")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    implementation("androidx.core:core-ktx:1.10.1")
    implementation("androidx.appcompat:appcompat:1.6.1")

    // pedroSG94's Camera1 RTMP encoder — the last v2.2.x release where
    // the library still ships a RtmpCamera1 API that works on minSdk 16.
    // v2.3+ dropped Camera1 support entirely.
    implementation("com.github.pedroSG94:rtmp-rtsp-stream-client-java:2.2.6")
}
