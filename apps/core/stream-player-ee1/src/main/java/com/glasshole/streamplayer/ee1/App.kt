package com.glasshole.streamplayer.ee1

import androidx.multidex.MultiDexApplication
import org.conscrypt.Conscrypt
import java.security.Security

class App : MultiDexApplication() {
    override fun onCreate() {
        super.onCreate()
        // Install Conscrypt as the default security provider.
        // This gives API 19 modern TLS 1.2/1.3 with current cipher suites.
        Security.insertProviderAt(Conscrypt.newProvider(), 1)
    }
}
