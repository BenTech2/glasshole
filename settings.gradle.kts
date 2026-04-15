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
include(":plugin-stream-glass")
include(":plugin-device-glass")
include(":plugin-gallery-glass")
