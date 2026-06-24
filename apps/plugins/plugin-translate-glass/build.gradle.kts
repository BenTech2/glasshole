plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

apply(from = "${rootDir}/build-versioning.gradle.kts")

android {
    namespace = "com.glasshole.plugin.translate.glass"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.glasshole.plugin.translate.glass"
        // Camera1 + minSdk 19 so one binary works on XE / EE1 / EE2.
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

    lint {
        // Targeting API 19 is intentional for EE1 / XE compatibility.
        disable += "ExpiredTargetSdkVersion"
        abortOnError = false
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
    implementation("androidx.core:core-ktx:1.10.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
}
