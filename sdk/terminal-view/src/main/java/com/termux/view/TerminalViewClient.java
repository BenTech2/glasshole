package com.termux.view;

import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import com.termux.terminal.TerminalSession;

/**
 * Callbacks from {@link TerminalView} to its host. GlassHole-modified
 * from upstream Termux: every method is a default no-op (or returns
 * a sensible default) so a host activity only overrides what it
 * cares about. Same rationale as TerminalSessionClient — the
 * upstream "implement all 20" surface is overkill for an embedded
 * SSH terminal.
 */
public interface TerminalViewClient {

    /** Callback on scale events; return the new font size in sp.
     *  Default: 0 — TerminalView treats 0 as "no scale change". */
    default float onScale(float scale) { return 0f; }

    /** Single tap when terminal mouse reporting is disabled. */
    default void onSingleTapUp(MotionEvent e) {}

    default boolean shouldBackButtonBeMappedToEscape() { return false; }

    default boolean shouldEnforceCharBasedInput() { return false; }

    default boolean shouldUseCtrlSpaceWorkaround() { return false; }

    default boolean isTerminalViewSelected() { return true; }

    default void copyModeChanged(boolean copyMode) {}

    default boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession session) { return false; }

    default boolean onKeyUp(int keyCode, KeyEvent e) { return false; }

    default boolean onLongPress(MotionEvent event) { return false; }

    default boolean readControlKey() { return false; }

    default boolean readAltKey() { return false; }

    default boolean readShiftKey() { return false; }

    default boolean readFnKey() { return false; }

    default boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession session) { return false; }

    default void onEmulatorSet() {}

    default void logError(String tag, String message) {}

    default void logWarn(String tag, String message) {}

    default void logInfo(String tag, String message) {}

    default void logDebug(String tag, String message) {}

    default void logVerbose(String tag, String message) {}

    default void logStackTraceWithMessage(String tag, String message, Exception e) {}

    default void logStackTrace(String tag, Exception e) {}
}
