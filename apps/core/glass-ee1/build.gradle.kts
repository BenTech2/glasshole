plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

extra["versionFamily"] = listOf(":glass-ee1", ":glass-ee2", ":glass-xe")
apply(from = "${rootDir}/build-versioning.gradle.kts")

android {
    namespace = "com.glasshole.glassee1"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.glasshole.glassee1"
        minSdk = 19
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

    // Two distributions share the same code + resources but ship as
    // separate APKs with different applicationIds so they install
    // side-by-side. Standalone is the regular app-drawer entry; Launcher
    // adds android.intent.category.HOME so Glass treats it as a valid
    // home replacement (matches glass-ee2's flavor split).
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

    // Emit BuildConfig so HomeActivity can branch on FLAVOR (launcher vs
    // standalone) — swipe-down sleeps in launcher mode, finish()es in
    // standalone mode.
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(project(":glass-plugin-sdk"))
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.viewpager2:viewpager2:1.0.0")
}
