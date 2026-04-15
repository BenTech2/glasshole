package com.glasshole.sdk;

import com.glasshole.sdk.PluginMessage;
import com.glasshole.sdk.IPluginCallback;

interface IPluginHost {
    void registerPlugin(String pluginId, IPluginCallback callback);
    void unregisterPlugin(String pluginId);
    boolean sendToGlass(String pluginId, in PluginMessage message);
    boolean isGlassConnected();
}
