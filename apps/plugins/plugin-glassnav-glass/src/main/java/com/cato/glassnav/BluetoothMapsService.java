// SPDX-License-Identifier: GPL-3.0-or-later
// SHIM — keeps only the `MessageConstants` inner interface from
// upstream GlassNav's BluetoothMapsService. MainActivity references
// `BluetoothMapsService.MessageConstants.MESSAGE_READ` etc. in its
// Handler; we don't want to globally edit MainActivity, so we keep
// the interface around. All the actual RFCOMM socket logic has moved
// to `GlassNavPluginService.kt` which uses GlassHole's BT bridge
// instead. `start()` / `stop()` / `write()` are no-ops because the
// underlying transport is now owned by the GlassHole launcher's
// `BluetoothListenerService` — it stays connected for the whole
// session, not per-activity.
package com.cato.glassnav;

import android.os.Handler;
import android.util.Log;

public class BluetoothMapsService {
    private static final String TAG = "BluetoothMapsService";

    public BluetoothMapsService(MainActivity activity, Handler handler) {
        // No-op constructor — transport is owned by GlassNavPluginService.
    }

    public synchronized void start() {
        Log.d(TAG, "start() — no-op, transport owned by GlassNavPluginService");
    }

    public synchronized void stop() {
        Log.d(TAG, "stop() — no-op");
    }

    public interface MessageConstants {
        int MESSAGE_READ = 0;
        int MESSAGE_WRITE = 1;
        int MESSAGE_ERROR = 2;
    }
}
