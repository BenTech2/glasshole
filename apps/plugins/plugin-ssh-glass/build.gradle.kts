plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

apply(from = "${rootDir}/build-versioning.gradle.kts")

android {
    namespace = "com.glasshole.plugin.ssh.glass"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.glasshole.plugin.ssh.glass"
        // Drops to 19 so the same APK runs on EE1 + XE (KitKat)
        // alongside EE2. Tradeoff: EncryptedSharedPreferences requires
        // API 23+ for Keystore-wrapped AES-GCM keys, so on EE1/XE the
        // SSH profile + manual-entry stores fall back to plain
        // SharedPreferences (app-sandbox protection only). The Phone
        // SSH page + on-glass picker both surface this with a
        // disclaimer; see EncryptedPrefs.get for the runtime guard.
        minSdk = 19
        targetSdk = 34
        versionCode = (project.extra["computedVersionCode"] as Int)
        versionName = (project.extra["computedVersionName"] as String)
        // Required for core-library desugaring to chain through the
        // 64k method ceiling — the Termux emulator + JSch + JCE shim
        // push us past it on KitKat.
        multiDexEnabled = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        // Java 8 language features only — AGP's stock backporter
        // handles lambdas / default methods. Core-library desugaring
        // was enabled briefly but conflicted with BouncyCastle's
        // Collections-bridge bytecode in D8; if a missing
        // java.util.* class shows up at runtime we can switch back to
        // selectively desugaring those classes.
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation(project(":glass-plugin-sdk"))
    implementation(project(":terminal-view"))
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    // AES-GCM file encryption with a Keystore-wrapped master key.
    // Used to protect saved SSH passwords + private keys at rest on
    // API 23+. EncryptedPrefs.get falls back to plain SharedPreferences
    // on KitKat (EE1 / XE) since the Keystore APIs aren't available
    // there — the user-facing disclaimers note this.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // mwiede fork of JSch — same API as the original com.jcraft:jsch but
    // with modern algorithms (Ed25519, ECDSA, AES-GCM, curve25519-sha256
    // KEX). Maven Central, no native deps.
    implementation("com.github.mwiede:jsch:0.2.16")
    // BouncyCastle. mwiede/jsch routes Ed25519, X25519 and a chunk of
    // ECDSA / AES-GCM through BC; on KitKat (EE1 / XE) those algorithms
    // aren't in the platform JCE provider so the SSH handshake fails
    // mid-KEX without BC on the classpath. EE2 has them in-platform but
    // pulling BC unconditionally keeps the APK behavior identical
    // across editions and only adds a few MB. The jdk15to18 build is
    // the right one for Android: it targets Java 7 / Android-friendly
    // bytecode and runs on Dalvik (KitKat) just as well as ART.
    implementation("org.bouncycastle:bcprov-jdk15on:1.70")
}
