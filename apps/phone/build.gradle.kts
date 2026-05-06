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
}
