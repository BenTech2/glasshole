plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.glasshole.plugin.broadcast.glass"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.glasshole.plugin.broadcast.glass"
        // EE2 only — Camera2 works on API 21+ but the encoder library
        // expects API 21+ plus some API 26 MediaCodec behaviour.
        minSdk = 27
        targetSdk = 34
        versionCode = 2
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

    // pedroSG94's RootEncoder — camera2 capture + H.264/AAC encoding +
    // RTMP push in one library. v2.3.0 renamed the packages to
    // com.pedro.library.* and split the ConnectChecker callback.
    implementation("com.github.pedroSG94.RootEncoder:library:2.5.2")
}
