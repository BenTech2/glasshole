// SPDX-License-Identifier: MIT
package com.glasshole.plugin.skymap.glass

import com.glasshole.glass.sdk.GlassPluginMessage
import com.glasshole.glass.sdk.GlassPluginService

/**
 * SkyMap plugin service. Currently a no-op shell — the plugin runs
 * entirely on-glass (sensors + bundled star catalog). The service
 * exists so the GlassHole plugin discovery system picks SkyMap up
 * as a launcher tile + lets us add phone-side features later
 * (e.g. observer-location overrides, custom time, satellite TLE
 * downloads).
 */
class SkyMapPluginService : GlassPluginService() {

    override val pluginId: String = "skymap"

    override fun onMessageFromPhone(message: GlassPluginMessage) {
        // Nothing yet — all sky math runs locally on glass.
    }
}
