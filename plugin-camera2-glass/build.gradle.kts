plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.glasshole.plugin.camera2.glass"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.glasshole.plugin.camera2.glass"
        // EE2 is API 27 — this plugin targets EE2 only. Camera2 API ships on API 21+.
        minSdk = 27
        targetSdk = 34
        versionCode = 3
        versionName = "0.3.0-alpha"
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
}

dependencies {
    implementation(project(":glass-plugin-sdk"))
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
}
