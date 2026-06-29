// SPDX-License-Identifier: Apache-2.0
// GDK STUBS — compile-time only. Real Google Glass devices (XE / EE1 /
// EE2) provide the actual GDK classes at runtime via the OS, so these
// stubs are intentionally NOT bundled into the APK (the consuming
// plugin declares this with `compileOnly(project(":glass-gdk-stubs"))`).
//
// The stubs cover only the API surface that GlassNav's MainActivity /
// SearchActivity / RouteActivity actually call — not the whole GDK.
// They have no implementation; methods return defaults so we just
// satisfy the compiler.
//
// Built as an Android library because the stubs reference Android
// framework types (Context, View, MotionEvent, BaseAdapter, etc.)
// which a plain java-library module can't see.
plugins {
    id("com.android.library")
}

android {
    namespace = "com.google.android.glass.stubs"
    compileSdk = 34
    defaultConfig { minSdk = 19 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
