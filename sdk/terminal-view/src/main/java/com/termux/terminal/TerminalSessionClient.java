package com.termux.terminal;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Callbacks from {@link TerminalSession} to its host. GlassHole-modified
 * from upstream Termux: every method is a default no-op so a host
 * activity only needs to override the handful it actually cares about
 * (typically onSessionFinished + onTextChanged for a redraw poke).
 *
 * Upstream's strict "implement all 14" surface was reasonable for a
 * full terminal app; for an embedded SSH viewer most of the logging /
 * cursor-state hooks are noise.
 */
public interface TerminalSessionClient {

    default void onTextChanged(@NonNull TerminalSession changedSession) {}

    default void onTitleChanged(@NonNull TerminalSession changedSession) {}

    default void onSessionFinished(@NonNull TerminalSession finishedSession) {}

    default void onCopyTextToClipboard(@NonNull TerminalSession session, String text) {}

    default void onPasteTextFromClipboard(@Nullable TerminalSession session) {}

    default void onBell(@NonNull TerminalSession session) {}

    default void onColorsChanged(@NonNull TerminalSession session) {}

    default void onTerminalCursorStateChange(boolean state) {}

    default void setTerminalShellPid(@NonNull TerminalSession session, int pid) {}

    default Integer getTerminalCursorStyle() { return null; }

    default void logError(String tag, String message) {}

    default void logWarn(String tag, String message) {}

    default void logInfo(String tag, String message) {}

    default void logDebug(String tag, String message) {}

    default void logVerbose(String tag, String message) {}

    default void logStackTraceWithMessage(String tag, String message, Exception e) {}

    default void logStackTrace(String tag, Exception e) {}
}
