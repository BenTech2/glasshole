pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// Foojay toolchain resolver: lets Gradle auto-download a Java 21 JDK
// for plugin-glassnav-glass without polluting the system. Other
// modules use the locally-installed Java 11 as before.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "GlassHole"

// Shared libraries (not installed on-device)
include(":plugin-sdk")
project(":plugin-sdk").projectDir = file("sdk/plugin-sdk")
include(":glass-plugin-sdk")
project(":glass-plugin-sdk").projectDir = file("sdk/glass-plugin-sdk")
include(":terminal-view")
project(":terminal-view").projectDir = file("sdk/terminal-view")

// MapLibre navigation core — vendored fork (KitKat-compatible) used by
// plugin-glassnav-glass for turn-by-turn routing logic. Includes shim
// classes that bridge to the Java-based maplibre-gl 6.0.1 artifacts
// instead of the Kotlin 2.x 7.0.0-pre0 (which we can't load).
include(":maplibre-navigation-core")
project(":maplibre-navigation-core").projectDir = file("apps/lib/maplibre-navigation-core")

// Google Glass GDK stubs — compile-time only. Lets us build code that
// uses `com.google.android.glass.*` types (CardBuilder, GestureDetector,
// etc.) without bundling them in the APK. Real Glass devices provide
// the actual GDK at runtime; this module is `compileOnly` from the
// plugin.
include(":glass-gdk-stubs")
project(":glass-gdk-stubs").projectDir = file("apps/lib/glass-gdk-stubs")

// Phone companion app
include(":phone")
project(":phone").projectDir = file("apps/phone")

// Core glass APKs — base app, Stream Player, and plugin services that back
// built-in functionality (device controls, photo sync). Not shown in the
// on-glass plugin carousel and badged "Core" in the phone's APK Manager.
include(":glass-ee1")
project(":glass-ee1").projectDir = file("apps/core/glass-ee1")
include(":glass-ee2")
project(":glass-ee2").projectDir = file("apps/core/glass-ee2")
include(":glass-xe")
project(":glass-xe").projectDir = file("apps/core/glass-xe")
include(":stream-player-ee1")
project(":stream-player-ee1").projectDir = file("apps/core/stream-player-ee1")
include(":stream-player-ee2")
project(":stream-player-ee2").projectDir = file("apps/core/stream-player-ee2")
include(":stream-player-xe")
project(":stream-player-xe").projectDir = file("apps/core/stream-player-xe")
include(":plugin-device-glass")
project(":plugin-device-glass").projectDir = file("apps/core/plugin-device-glass")
include(":plugin-gallery-glass")
project(":plugin-gallery-glass").projectDir = file("apps/core/plugin-gallery-glass")

// User-launchable plugins — appear in the on-glass plugin carousel.
include(":plugin-notes-glass")
project(":plugin-notes-glass").projectDir = file("apps/plugins/plugin-notes-glass")
include(":plugin-calc-glass")
project(":plugin-calc-glass").projectDir = file("apps/plugins/plugin-calc-glass")
include(":plugin-gallery2-glass")
project(":plugin-gallery2-glass").projectDir = file("apps/plugins/plugin-gallery2-glass")
include(":plugin-camera2-glass")
project(":plugin-camera2-glass").projectDir = file("apps/plugins/plugin-camera2-glass")
include(":plugin-openclaw-glass")
project(":plugin-openclaw-glass").projectDir = file("apps/plugins/plugin-openclaw-glass")
include(":plugin-chat-glass")
project(":plugin-chat-glass").projectDir = file("apps/plugins/plugin-chat-glass")
// plugin-nav-glass — retired in M3 of the Home rework. Nav now lives as
// a card inside HomeActivity. Source kept for reference; removed from
// the build.
// include(":plugin-nav-glass")
// project(":plugin-nav-glass").projectDir = file("apps/plugins/plugin-nav-glass")
include(":plugin-compass-glass")
project(":plugin-compass-glass").projectDir = file("apps/plugins/plugin-compass-glass")
include(":plugin-scouter-glass")
project(":plugin-scouter-glass").projectDir = file("apps/plugins/plugin-scouter-glass")
include(":plugin-translate-glass")
project(":plugin-translate-glass").projectDir = file("apps/plugins/plugin-translate-glass")
// plugin-media-glass — retired in M2 of the Home rework. Media now lives
// inside the base app as a card in HomeActivity. Source kept for
// reference; removed from the build.
// include(":plugin-media-glass")
// project(":plugin-media-glass").projectDir = file("apps/plugins/plugin-media-glass")
include(":plugin-broadcast-glass")
project(":plugin-broadcast-glass").projectDir = file("apps/plugins/plugin-broadcast-glass")
include(":plugin-opencv-glass")
project(":plugin-opencv-glass").projectDir = file("apps/plugins/plugin-opencv-glass")
include(":plugin-broadcast-legacy-glass")
project(":plugin-broadcast-legacy-glass").projectDir = file("apps/plugins/plugin-broadcast-legacy-glass")
include(":plugin-ssh-glass")
project(":plugin-ssh-glass").projectDir = file("apps/plugins/plugin-ssh-glass")
include(":plugin-devtools-glass")
project(":plugin-devtools-glass").projectDir = file("apps/plugins/plugin-devtools-glass")
include(":plugin-aiassistant-glass")
project(":plugin-aiassistant-glass").projectDir = file("apps/plugins/plugin-aiassistant-glass")
include(":plugin-glassnav-glass")
project(":plugin-glassnav-glass").projectDir = file("apps/plugins/plugin-glassnav-glass")
