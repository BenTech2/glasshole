// SPDX-License-Identifier: Apache-2.0
// Stub of com.google.android.glass.media.Sounds — Glass GDK provides
// the real implementation at runtime. The int constants here are the
// canonical GDK sound-effect IDs (verified against open-quartz mirror
// and the GDK source dump). On non-Glass devices `playSoundEffect`
// silently does nothing.
package com.google.android.glass.media;

public class Sounds {
    public static final int TAP = 4;
    public static final int SELECTED = 1;
    public static final int DISALLOWED = 5;
    public static final int DISMISSED = 6;
    public static final int SUCCESS = 7;
    public static final int ERROR = 8;
    public static final int VIDEO_NOT_READY = 9;
    public static final int VIDEO_STOP = 12;
}
