plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.glasshole.glassee2"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.glasshole.glassee2"
        minSdk = 27 // Glass EE2 runs Android 8.1 (API 27)
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

    // Two distributions share the same code + resources but ship as
    // separate APKs with different applicationIds so they can install
    // side-by-side. Standalone is the regular app-drawer entry; Launcher
    // adds android.intent.category.HOME so Glass will offer it as the
    // default home replacement.
    flavorDimensions += "distribution"
    productFlavors {
        create("standalone") {
            dimension = "distribution"
        }
        create("launcher") {
            dimension = "distribution"
            applicationIdSuffix = ".launcher"
            versionNameSuffix = "-launcher"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    // Emit BuildConfig so runtime code can branch on FLAVOR (launcher vs
    // standalone) for behaviors that only make sense in one variant —
    // e.g. swipe-down sleeping the glass instead of finish()ing Home.
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(project(":glass-plugin-sdk"))
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.viewpager2:viewpager2:1.0.0")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
}
