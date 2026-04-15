package com.glasshole.glass.sdk;

import com.glasshole.glass.sdk.GlassPluginMessage;
import com.glasshole.glass.sdk.IGlassPluginCallback;

interface IGlassPluginHost {
    void registerGlassPlugin(String pluginId, IGlassPluginCallback callback);
    void unregisterGlassPlugin(String pluginId);
    boolean sendToPhone(String pluginId, in GlassPluginMessage message);
    boolean isPhoneConnected();
}
