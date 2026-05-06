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
// Version families: a module can opt into a shared counter with
// other modules by declaring, *before* applying this script:
//
//     extra["versionFamily"] = listOf(":glass-ee1", ":glass-ee2", ":glass-xe")
//
// When set, the read takes the max counter across the family and
// every successful package writes that new value back to *all*
// family members' counter files. This keeps the per-edition glass
// APKs (and stream players) in lockstep so a build of one edition
// also bumps the others — even when only one edition is built —
// preventing INSTALL_FAILED_VERSION_DOWNGRADE collisions when
// flashing the same code across multiple Glass devices.
//
// Modules without a `versionFamily` keep their own per-module
// counter (phone, plugins, anything single-APK).

val familyPaths: List<String> = (project.extra.properties["versionFamily"] as? List<*>)
    ?.filterIsInstance<String>()
    ?.takeIf { it.isNotEmpty() }
    ?: listOf(project.path)

val familyCounterFiles: List<java.io.File> = familyPaths.map { path ->
    rootProject.project(path).file("build_counter.txt")
}

val currentCounter: Int = familyCounterFiles.mapNotNull { f ->
    if (f.exists()) f.readText().trim().toIntOrNull() else null
}.maxOrNull() ?: 0
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
        for (f in familyCounterFiles) {
            try {
                f.writeText(nextCounter.toString())
            } catch (e: Exception) {
                println("⚠️  ${project.name}: failed to write counter ${f.path}: ${e.message}")
            }
        }
        val tag = if (familyPaths.size > 1) " [family: ${familyPaths.joinToString(",")}]" else ""
        println("📈 ${project.name}: build counter -> $nextCounter (v$baseVersion)$tag")
    }
}
