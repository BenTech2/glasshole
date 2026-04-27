plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.glasshole.plugin.gallery2.glass"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.glasshole.plugin.gallery2.glass"
        // Lowered from 27 → 19 so the cover-flow gallery installs on
        // Glass XE / EE1 (KitKat) too. translationZ + a few other API 21+
        // properties are runtime-gated.
        minSdk = 19
        targetSdk = 34
        versionCode = 4
        versionName = "0.4.0-alpha"
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
    implementation("androidx.viewpager2:viewpager2:1.0.0")
}
