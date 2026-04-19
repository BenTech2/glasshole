pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
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
include(":plugin-broadcast-glass")
project(":plugin-broadcast-glass").projectDir = file("apps/plugins/plugin-broadcast-glass")
include(":plugin-broadcast-legacy-glass")
project(":plugin-broadcast-legacy-glass").projectDir = file("apps/plugins/plugin-broadcast-legacy-glass")
