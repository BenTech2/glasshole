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
include(":plugin-sdk")
include(":glass-plugin-sdk")
include(":phone")
include(":glass-ee2")
include(":glass-ee1")
include(":glass-xe")
include(":plugin-notes-glass")
include(":plugin-calc-glass")
include(":plugin-device-glass")
include(":plugin-gallery-glass")
include(":plugin-camera2-glass")
include(":plugin-gallery2-glass")
include(":stream-viewer-ee1")
include(":stream-viewer-ee2")
include(":stream-viewer-xe")
