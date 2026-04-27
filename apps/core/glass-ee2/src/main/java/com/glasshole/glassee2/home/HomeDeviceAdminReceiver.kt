package com.glasshole.glassee2.home

import android.app.admin.DeviceAdminReceiver

/**
 * Minimal DeviceAdminReceiver — our sole policy is `force-lock`, which
 * unlocks [android.app.admin.DevicePolicyManager.lockNow] so Home can
 * put the display to sleep instantly on idle without relying on
 * accessibility or root. The receiver has no active behavior of its
 * own; it exists purely to satisfy the framework's
 * "apps requesting admin rights must declare a receiver" requirement.
 */
class HomeDeviceAdminReceiver : DeviceAdminReceiver()
