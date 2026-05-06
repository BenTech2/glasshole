plugins {
    id("com.android.library")
}

android {
    namespace = "com.termux.view"
    compileSdk = 34

    defaultConfig {
        // Down from 26 so the SSH plugin can ship to EE1 / XE
        // (API 19). Termux's terminal modules are pure-Java and rely
        // on framework APIs that exist back to KitKat — Java 8
        // language features compile through Android's built-in
        // desugaring.
        minSdk = 19
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.7.0")
}
