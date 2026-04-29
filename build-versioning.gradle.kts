// Per-module APK versioning helper.
//
// Apply from each android-application module's build.gradle.kts:
//
//     apply(from = "${rootDir}/build-versioning.gradle.kts")
//
// Then in the android { defaultConfig { ... } } block, use:
//
//     versionCode = (project.extra["computedVersionCode"] as Int)
//     versionName = (project.extra["computedVersionName"] as String)
//
// What it does:
//   - Reads `build_counter.txt` from the module's project dir
//     (default 0 when missing).
//   - Computes the *next* counter and exposes it as
//     project.extra["computedVersionCode"].
//   - Composes versionName from the root project's `glassholeVersion`
//     gradle property (default "0.0.0") so all modules stay in sync
//     with the current branch version.
//   - On a successful gradle invocation that included any
//     `package*` task for this module, writes the new counter back
//     to disk so the next build picks it up. Failed builds and
//     pure-config invocations (`./gradlew tasks`, etc.) leave the
//     counter alone.
//
// Counters are PER-MODULE: glass-ee2 / phone / each plugin / each
// stream player keep their own files so build numbers don't collide.

val counterFile = project.file("build_counter.txt")
val currentCounter: Int = if (counterFile.exists()) {
    counterFile.readText().trim().toIntOrNull() ?: 0
} else 0
val nextCounter: Int = currentCounter + 1

val baseVersion: String = (project.rootProject.findProperty("glassholeVersion") as? String) ?: "0.0.0"

project.extra["computedVersionCode"] = nextCounter
project.extra["computedVersionName"] = baseVersion

// Persist the bumped counter once we know the build is actually
// packaging APKs and didn't fail.
var willPackage = false
gradle.taskGraph.whenReady {
    val modulePrefix = ":${project.name}:"
    willPackage = allTasks.any { task ->
        task.path.startsWith(modulePrefix) && task.name.startsWith("package")
    }
}
gradle.buildFinished {
    if (failure == null && willPackage) {
        try {
            counterFile.writeText(nextCounter.toString())
            println("📈 ${project.name}: build counter -> $nextCounter (v$baseVersion)")
        } catch (e: Exception) {
            println("⚠️  ${project.name}: failed to write build counter: ${e.message}")
        }
    }
}
