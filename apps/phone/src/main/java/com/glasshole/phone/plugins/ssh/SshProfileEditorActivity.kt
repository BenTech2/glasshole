package com.glasshole.phone.plugins.ssh

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.glasshole.phone.R
import com.glasshole.phone.service.BridgeService
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.textfield.TextInputLayout

/**
 * Single-page SSH profile editor — handles add (no extra) and edit
 * (EXTRA_PROFILE_ID). Save persists locally and pushes a fresh
 * SET_PROFILES envelope to the glass so the cache stays in sync.
 */
class SshProfileEditorActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_ID = "profile_id"

        fun intent(context: Context, profileId: String?): Intent =
            Intent(context, SshProfileEditorActivity::class.java).apply {
                if (profileId != null) putExtra(EXTRA_ID, profileId)
            }
    }

    private lateinit var store: SshProfileStore
    private var existingId: String? = null

    private var bridge: BridgeService? = null
    private var bridgeBound = false
    private val bridgeConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, b: IBinder?) {
            bridge = (b as BridgeService.LocalBinder).getService()
            bridgeBound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            bridge = null
            bridgeBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ssh_profile_editor)

        store = SshProfileStore(this)
        existingId = intent.getStringExtra(EXTRA_ID)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = if (existingId == null) "Add connection" else "Edit connection"
        toolbar.setNavigationOnClickListener { finish() }

        val nameInput = findViewById<EditText>(R.id.nameInput)
        val hostInput = findViewById<EditText>(R.id.hostInput)
        val portInput = findViewById<EditText>(R.id.portInput)
        val userInput = findViewById<EditText>(R.id.userInput)
        val passwordInput = findViewById<EditText>(R.id.passwordInput)
        val keyInput = findViewById<EditText>(R.id.keyInput)
        val keyPassphraseInput = findViewById<EditText>(R.id.keyPassphraseInput)
        val authToggle = findViewById<MaterialButtonToggleGroup>(R.id.authToggleGroup)
        val passwordLayout = findViewById<TextInputLayout>(R.id.passwordLayout)
        val keyLayout = findViewById<TextInputLayout>(R.id.keyLayout)
        val keyPassphraseLayout = findViewById<TextInputLayout>(R.id.keyPassphraseLayout)
        val pickSavedKeyButton = findViewById<MaterialButton>(R.id.pickSavedKeyButton)
        val saveButton = findViewById<MaterialButton>(R.id.saveButton)
        val deleteButton = findViewById<MaterialButton>(R.id.deleteButton)

        pickSavedKeyButton.setOnClickListener {
            val keys = SshKeyStore(this).list().sortedByDescending { it.createdAt }
            if (keys.isEmpty()) {
                android.widget.Toast.makeText(
                    this,
                    "No saved keys yet — generate one in Manage SSH keys",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            val labels = keys.map { it.name }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("Pick a key")
                .setItems(labels) { _, which ->
                    val k = keys[which]
                    keyInput.setText(k.privateKeyPem)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        existingId?.let { id ->
            store.get(id)?.let { p ->
                nameInput.setText(p.name)
                hostInput.setText(p.host)
                portInput.setText(p.port.toString())
                userInput.setText(p.user)
                passwordInput.setText(p.password.orEmpty())
                keyInput.setText(p.privateKeyPem.orEmpty())
                keyPassphraseInput.setText(p.keyPassphrase.orEmpty())
                authToggle.check(
                    if (p.authMode == SshProfileStore.AuthMode.KEY) R.id.authKeyButton
                    else R.id.authPasswordButton
                )
                deleteButton.visibility = View.VISIBLE
            }
        } ?: authToggle.check(R.id.authPasswordButton)

        // Initial visibility based on whatever we just selected.
        applyAuthVisibility(
            authToggle.checkedButtonId == R.id.authKeyButton,
            passwordLayout, keyLayout, keyPassphraseLayout, pickSavedKeyButton
        )
        authToggle.addOnButtonCheckedListener { _, _, _ ->
            applyAuthVisibility(
                authToggle.checkedButtonId == R.id.authKeyButton,
                passwordLayout, keyLayout, keyPassphraseLayout, pickSavedKeyButton
            )
        }

        saveButton.setOnClickListener {
            val name = nameInput.text.toString().trim()
            val host = hostInput.text.toString().trim()
            val port = portInput.text.toString().trim().toIntOrNull() ?: 22
            val user = userInput.text.toString().trim()
            val authMode = if (authToggle.checkedButtonId == R.id.authKeyButton)
                SshProfileStore.AuthMode.KEY else SshProfileStore.AuthMode.PASSWORD
            if (host.isEmpty() || user.isEmpty()) {
                Toast.makeText(this, "Host and user required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val keyPem = keyInput.text.toString().takeIf { it.isNotBlank() }?.trim()
            if (authMode == SshProfileStore.AuthMode.KEY && keyPem.isNullOrEmpty()) {
                Toast.makeText(this, "Paste the private key PEM", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val profile = SshProfileStore.Profile(
                id = existingId ?: java.util.UUID.randomUUID().toString(),
                name = name.ifEmpty { "$user@$host" },
                host = host,
                port = port,
                user = user,
                authMode = authMode,
                password = passwordInput.text.toString().takeIf {
                    authMode == SshProfileStore.AuthMode.PASSWORD && it.isNotEmpty()
                },
                privateKeyPem = keyPem.takeIf { authMode == SshProfileStore.AuthMode.KEY },
                keyPassphrase = keyPassphraseInput.text.toString().takeIf {
                    authMode == SshProfileStore.AuthMode.KEY && it.isNotEmpty()
                }
            )
            store.upsert(profile)
            syncToGlass()
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            finish()
        }

        deleteButton.setOnClickListener {
            existingId?.let { id ->
                store.delete(id)
                syncToGlass()
                Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

        bindService(
            Intent(this, BridgeService::class.java),
            bridgeConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onDestroy() {
        if (bridgeBound) {
            unbindService(bridgeConnection)
            bridgeBound = false
        }
        super.onDestroy()
    }

    private fun applyAuthVisibility(
        keyMode: Boolean,
        passwordLayout: TextInputLayout,
        keyLayout: TextInputLayout,
        keyPassphraseLayout: TextInputLayout,
        pickSavedKeyButton: MaterialButton
    ) {
        passwordLayout.visibility = if (keyMode) View.GONE else View.VISIBLE
        keyLayout.visibility = if (keyMode) View.VISIBLE else View.GONE
        keyPassphraseLayout.visibility = if (keyMode) View.VISIBLE else View.GONE
        pickSavedKeyButton.visibility = if (keyMode) View.VISIBLE else View.GONE
    }

    private fun syncToGlass() {
        val b = bridge ?: return
        if (b.isConnected) b.sendPluginMessage("ssh", "SET_PROFILES", store.snapshotJson())
    }
}
