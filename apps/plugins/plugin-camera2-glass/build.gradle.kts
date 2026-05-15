plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

apply(from = "${rootDir}/build-versioning.gradle.kts")

android {
    namespace = "com.glasshole.plugin.camera2.glass"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.glasshole.plugin.camera2.glass"
        // EE2 is API 27 — this plugin targets EE2 only. Camera2 API ships on API 21+.
        minSdk = 27
        targetSdk = 34
        versionCode = (project.extra["computedVersionCode"] as Int)
        versionName = (project.extra["computedVersionName"] as String)
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    // Reflective access to LED_AVAILABLE_LEDS / LED_TRANSMIT keeps the
    // IR-LED control alive on the Glass EE2 firmwares that expose it.
    // BlockedPrivateApi is a hard lint error at compileSdk 34; we
    // ship debug-signed builds and accept the SDK-incompat risk
    // because EE2 firmware never moves past API 27.
    lint {
        disable += "BlockedPrivateApi"
    }
}

dependencies {
    implementation(project(":glass-plugin-sdk"))
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
}
