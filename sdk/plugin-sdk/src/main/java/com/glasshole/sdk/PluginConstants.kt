package com.glasshole.sdk

object PluginConstants {
    // Intent action for discovering phone-side plugins
    const val ACTION_PHONE_PLUGIN = "com.glasshole.plugin.PHONE_PLUGIN"

    // Permission for binding to plugins
    const val PERMISSION_BIND_PLUGIN = "com.glasshole.permission.BIND_PLUGIN"

    // Plugin metadata keys (declared in AndroidManifest meta-data)
    const val META_PLUGIN_ID = "com.glasshole.plugin.ID"
    const val META_PLUGIN_NAME = "com.glasshole.plugin.NAME"
    const val META_PLUGIN_VERSION = "com.glasshole.plugin.VERSION"
    const val META_PLUGIN_ICON = "com.glasshole.plugin.ICON"
    const val META_GLASS_PACKAGE = "com.glasshole.plugin.GLASS_PACKAGE"

    // BT protocol prefixes
    const val PREFIX_PLUGIN = "PLUGIN:"
    const val PREFIX_MSG = "MSG:"
    const val PREFIX_REPLY = "REPLY:"
    const val PREFIX_INFO = "INFO:"
    const val PREFIX_INSTALL = "INSTALL:"
    const val PREFIX_INSTALL_DATA = "INSTALL_DATA:"

    const val CMD_PING = "PING"
    const val CMD_PONG = "PONG"
    const val CMD_INFO_REQ = "INFO_REQ"
    const val CMD_INSTALL_END = "INSTALL_END"
}
