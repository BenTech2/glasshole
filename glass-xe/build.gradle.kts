plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.glasshole.glassxe"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.glasshole.glassxe"
        minSdk = 19
        targetSdk = 19
        versionCode = 2
        versionName = "0.2.0-alpha"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    lint {
        // Targeting API 19 is intentional — Glass XE runs KitKat and there
        // is no newer SDK to move to. The release-blocking lint check is
        // irrelevant here.
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
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
}
