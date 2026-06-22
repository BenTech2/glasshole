plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

apply(from = "${rootDir}/build-versioning.gradle.kts")

android {
    namespace = "com.glasshole.plugin.devtools.glass"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.glasshole.plugin.devtools.glass"
        // minSdk 19 so this single APK runs on EE1 (KitKat) and XE
        // (KitKat) as well as EE2 (Oreo). No per-edition variants.
        minSdk = 19
        targetSdk = 34
        versionCode = (project.extra["computedVersionCode"] as Int)
        versionName = (project.extra["computedVersionName"] as String)
        // MINA SSHD + dependencies blow past the 64K method limit, and
        // we still want this plugin to install on KitKat (where
        // multidex isn't automatic) so the IP / ADB-toggle features
        // remain usable on EE1/XE.
        multiDexEnabled = true
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

    // MINA SSHD jars each ship their own META-INF/DEPENDENCIES file
    // (Apache 2 attribution boilerplate). The merge fails on collision
    // — just pick first, the resulting file is informational and not
    // part of the runtime path.
    packaging {
        resources {
            pickFirsts += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
            )
        }
    }
}

dependencies {
    implementation(project(":glass-plugin-sdk"))
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    // Runtime support for minSdk < 21 multidex APKs. EE2 (API 27)
    // ignores this; EE1/XE (API 19) need it to load the secondary dex.
    implementation("androidx.multidex:multidex:2.0.1")

    // Pure-Java embedded SSH server so the on-glass dev panel can spin
    // one up for remote recovery. Pure-Java sidesteps the per-edition
    // arch problem we'd hit shipping dropbear binaries (x86 on EE1,
    // ARMv7 on XE, ARM64 on EE2). 2.13 is the last release that
    // compiles cleanly against minSdk 19 without desugaring acrobatics.
    implementation("org.apache.sshd:sshd-core:2.13.2")
    // SCP support so the laptop can drop files onto the glass without
    // needing an interactive shell to drive `cat > file`.
    implementation("org.apache.sshd:sshd-scp:2.13.2")
    // MINA SSHD logs via SLF4J — provide a no-op binding so the logger
    // doesn't ClassNotFoundException at start time. The "real" output
    // already goes through our own Log.i calls.
    implementation("org.slf4j:slf4j-nop:2.0.13")
}
