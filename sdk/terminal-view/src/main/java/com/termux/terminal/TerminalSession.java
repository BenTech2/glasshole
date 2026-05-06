package com.termux.terminal;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * GlassHole replacement for Termux's PTY-coupled TerminalSession.
 * Wraps a {@link TerminalEmulator} but does NOT spawn a local
 * subprocess — Termux upstream relies on JNI + a master/slave pty
 * pair, and we don't need any of that for SSH (the remote sshd hosts
 * the pty). Instead this class exposes:
 *
 *   • {@link #feed(byte[], int)} — push bytes received from the
 *     remote (SSH InputStream) into the emulator on the main thread
 *   • {@link OutboundSink}        — caller-supplied lambda that
 *     receives bytes the emulator wants to send back (keystrokes,
 *     mouse events, OSC replies). Forward those into the SSH
 *     OutputStream.
 *   • {@link #updateSize(int, int, int, int)} — resize the emulator;
 *     callers should also push the new (cols, rows) over to the
 *     remote via SSH window-change request.
 *
 * The public API surface is intentionally a subset of upstream's
 * TerminalSession — only what TerminalView calls — so vendored
 * upstream view code keeps working unchanged. mHandle / mSessionName
 * are preserved as no-ops for that compatibility.
 */
public class TerminalSession extends TerminalOutput {

    /** Sink for emulator-to-remote bytes. The session calls this on
     *  the main thread (TerminalView dispatches keystrokes there). */
    public interface OutboundSink {
        void write(byte[] data, int offset, int count);
    }

    private static final int MSG_NEW_INPUT = 1;
    private static final int MSG_REMOTE_EXITED = 2;

    public final String mHandle = UUID.randomUUID().toString();
    public String mSessionName;

    private TerminalEmulator mEmulator;
    private TerminalSessionClient mClient;
    private final OutboundSink mSink;
    private final byte[] mUtf8InputBuffer = new byte[5];

    private final ByteQueue mProcessToTerminalIOQueue = new ByteQueue(64 * 1024);

    private boolean mFinished = false;
    private int mExitStatus = 0;

    private final Handler mMainThreadHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            if (msg.what == MSG_NEW_INPUT) {
                drainQueueIntoEmulator();
            } else if (msg.what == MSG_REMOTE_EXITED) {
                if (mClient != null) mClient.onSessionFinished(TerminalSession.this);
            }
        }
    };

    public TerminalSession(OutboundSink sink, TerminalSessionClient client) {
        this.mSink = sink;
        this.mClient = client;
    }

    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;
        if (mEmulator != null) mEmulator.updateTerminalSessionClient(client);
    }

    /** Push bytes received from the remote (SSH InputStream) toward
     *  the emulator. Safe to call from any thread; emulator processing
     *  happens on the main thread. */
    public void feed(byte[] data, int length) {
        if (mEmulator == null) {
            // Buffer until updateSize() initializes the emulator.
            mProcessToTerminalIOQueue.write(data, 0, length);
            return;
        }
        mProcessToTerminalIOQueue.write(data, 0, length);
        mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT);
    }

    /** Mark the session finished — caller invokes after SSH channel
     *  closes. Triggers TerminalSessionClient.onSessionFinished on the
     *  main thread. */
    public void notifyRemoteExited(int exitStatus) {
        mFinished = true;
        mExitStatus = exitStatus;
        mMainThreadHandler.sendEmptyMessage(MSG_REMOTE_EXITED);
    }

    public void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        if (mEmulator == null) {
            mEmulator = new TerminalEmulator(this, columns, rows, cellWidthPixels, cellHeightPixels, null, mClient);
            // If anything was buffered before the emulator existed,
            // drain it now so the user sees the connection banner.
            mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT);
        } else {
            mEmulator.resize(columns, rows, cellWidthPixels, cellHeightPixels);
        }
    }

    public String getTitle() {
        return (mEmulator == null) ? null : mEmulator.getTitle();
    }

    public TerminalEmulator getEmulator() {
        return mEmulator;
    }

    public boolean isRunning() {
        return !mFinished;
    }

    public int getExitStatus() {
        return mExitStatus;
    }

    public void reset() {
        if (mEmulator != null) mEmulator.reset();
        mProcessToTerminalIOQueue.write(new byte[0], 0, 0);
    }

    public void finishIfRunning() {
        if (!mFinished) {
            mFinished = true;
            if (mClient != null) mClient.onSessionFinished(this);
        }
    }

    // --- TerminalOutput overrides — emulator → remote ---

    @Override
    public void write(byte[] data, int offset, int count) {
        if (mFinished) return;
        if (mSink != null) mSink.write(data, offset, count);
    }

    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint < 0
            || (codePoint > 0xD7FF && codePoint < 0xE000)
            || codePoint > 0x10FFFF) {
            // Invalid UTF-8 code point — drop.
            return;
        }
        int bufferPosition = 0;
        if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 0x1B;
        if (codePoint <= 0x7F) {
            mUtf8InputBuffer[bufferPosition++] = (byte) codePoint;
        } else if (codePoint <= 0x7FF) {
            mUtf8InputBuffer[bufferPosition++] = (byte) (0xC0 | (codePoint >> 6));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0x80 | (codePoint & 0x3F));
        } else if (codePoint <= 0xFFFF) {
            mUtf8InputBuffer[bufferPosition++] = (byte) (0xE0 | (codePoint >> 12));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0x80 | ((codePoint >> 6) & 0x3F));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0x80 | (codePoint & 0x3F));
        } else {
            mUtf8InputBuffer[bufferPosition++] = (byte) (0xF0 | (codePoint >> 18));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0x80 | ((codePoint >> 12) & 0x3F));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0x80 | ((codePoint >> 6) & 0x3F));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0x80 | (codePoint & 0x3F));
        }
        write(mUtf8InputBuffer, 0, bufferPosition);
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        if (mClient != null) mClient.onTitleChanged(this);
    }

    @Override
    public void onCopyTextToClipboard(String text) {
        if (mClient != null) mClient.onCopyTextToClipboard(this, text);
    }

    @Override
    public void onPasteTextFromClipboard() {
        if (mClient != null) mClient.onPasteTextFromClipboard(this);
    }

    @Override
    public void onBell() {
        if (mClient != null) mClient.onBell(this);
    }

    @Override
    public void onColorsChanged() {
        if (mClient != null) mClient.onColorsChanged(this);
    }

    private void drainQueueIntoEmulator() {
        if (mEmulator == null) return;
        byte[] buffer = new byte[4096];
        while (true) {
            int n = mProcessToTerminalIOQueue.read(buffer, false);
            if (n <= 0) break;
            mEmulator.append(buffer, n);
        }
        if (mClient != null) mClient.onTextChanged(this);
    }
}
