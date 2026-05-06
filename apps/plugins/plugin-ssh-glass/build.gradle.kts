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
        // EE2-only for v1. Termux's terminal-view + JSch's Ed25519 path
        // both rely on Java 8 / API 26+ niceties (Optional, modern
        // SecureRandom, etc.) that XE / EE1 (API 19) can't carry without
        // significant desugaring work. Bumping back later is a one-line
        // change once the EE2 path is proven.
        minSdk = 26
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
}

dependencies {
    implementation(project(":glass-plugin-sdk"))
    implementation(project(":terminal-view"))
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    // AES-GCM file encryption with a Keystore-wrapped master key.
    // Used to protect saved SSH passwords + private keys at rest.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // mwiede fork of JSch — same API as the original com.jcraft:jsch but
    // with modern algorithms (Ed25519, ECDSA, AES-GCM, curve25519-sha256
    // KEX). Maven Central, no native deps.
    implementation("com.github.mwiede:jsch:0.2.16")
}
