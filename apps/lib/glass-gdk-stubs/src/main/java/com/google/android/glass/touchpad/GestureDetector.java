// SPDX-License-Identifier: Apache-2.0
// Stub of com.google.android.glass.touchpad.GestureDetector — the real
// thing wraps the Glass touchpad input pipeline and fires `Gesture`
// events. Real impl provided by the Glass GDK at runtime.
package com.google.android.glass.touchpad;

import android.content.Context;
import android.view.MotionEvent;

public class GestureDetector {
    public GestureDetector(Context context) { /* stub */ }

    public boolean onMotionEvent(MotionEvent event) { return false; }

    public GestureDetector setBaseListener(BaseListener listener) { return this; }
    public GestureDetector setFingerListener(FingerListener listener) { return this; }
    public GestureDetector setScrollListener(ScrollListener listener) { return this; }
    public GestureDetector setTwoFingerScrollListener(TwoFingerScrollListener listener) { return this; }

    public interface BaseListener {
        boolean onGesture(Gesture gesture);
    }
    public interface FingerListener {
        void onFingerCountChanged(int previousCount, int currentCount);
    }
    public interface ScrollListener {
        boolean onScroll(float displacement, float delta, float velocity);
    }
    public interface TwoFingerScrollListener {
        boolean onTwoFingerScroll(float displacement, float delta, float velocity);
    }
}
