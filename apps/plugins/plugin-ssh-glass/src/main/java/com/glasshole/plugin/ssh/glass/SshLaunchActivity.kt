package com.glasshole.plugin.ssh.glass

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Plugin launcher entry. Always routes to [ProfilePickerActivity] —
 * the picker handles both states (cached profiles + the empty-list
 * "Manual entry" jump) so this activity is just a redirect.
 */
class SshLaunchActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, ProfilePickerActivity::class.java))
        finish()
    }
}
