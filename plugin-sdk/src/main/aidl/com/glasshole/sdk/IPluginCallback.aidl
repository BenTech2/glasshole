package com.glasshole.sdk;

import com.glasshole.sdk.PluginMessage;

interface IPluginCallback {
    void onMessageFromGlass(in PluginMessage message);
    void onGlassConnectionChanged(boolean connected);
}
