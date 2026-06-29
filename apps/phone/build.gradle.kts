plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

apply(from = "${rootDir}/build-versioning.gradle.kts")

android {
    namespace = "com.glasshole.phone"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.glasshole.phone"
        minSdk = 24
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

    buildFeatures {
        // Need BuildConfig.VERSION_NAME on the home screen.
        buildConfig = true
    }
}

dependencies {
    implementation(project(":plugin-sdk"))
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.google.android.gms:play-services-location:21.1.0")
    // JSch (mwiede fork) — used by the SSH plugin's phone-side key
    // manager to generate Ed25519 keypairs on-device. Same dep as the
    // glass plugin; pure-Java, no native code.
    implementation("com.github.mwiede:jsch:0.2.16")
    // AES-GCM-encrypted SharedPreferences with a Keystore-wrapped master
    // key. Used by the SSH profile + key stores to protect saved
    // passwords and private keys at rest.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // ML Kit on-device text recognition + translation — used by the
    // Translate plugin. All inference runs locally on the phone after
    // a one-time model download (~25 MB OCR + ~30 MB per translation
    // language pair). Glass talks to the phone over BT; no network
    // calls from glass or phone post-download. Multiple recognizers
    // are pulled in so the user can switch source language without
    // a re-install (each script is its own model).
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    implementation("com.google.mlkit:translate:17.0.3")
}
