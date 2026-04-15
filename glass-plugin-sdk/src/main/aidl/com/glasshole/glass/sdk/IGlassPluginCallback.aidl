package com.glasshole.glass.sdk;

import com.glasshole.glass.sdk.GlassPluginMessage;

interface IGlassPluginCallback {
    void onMessageFromPhone(in GlassPluginMessage message);
    void onPhoneConnectionChanged(boolean connected);
}
