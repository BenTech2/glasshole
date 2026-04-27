plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.glasshole.plugin.opencv.glass"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.glasshole.plugin.opencv.glass"
        // EE2 is API 28. CameraX + ML Kit image labeling both work here.
        minSdk = 27
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-alpha"
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
    // CameraX — preview + image analysis stream.
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    // ML Kit object detection — bundled default detector that returns
    // bounding boxes + coarse classifications (5 categories). On-device.
    implementation("com.google.mlkit:object-detection:17.0.2")
}
