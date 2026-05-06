package com.glasshole.phone.plugins.ssh

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.glasshole.phone.R
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phone-side SSH key manager. Lets the user generate Ed25519 keypairs
 * on-device, copy the public key for pasting into a server's
 * authorized_keys, and pick a saved key when editing a profile.
 *
 * Generation runs on a background thread (Curve25519 keygen takes a
 * non-trivial fraction of a second on lower-end phones — enough to
 * jank the UI thread).
 */
class SshKeyManagerActivity : AppCompatActivity() {

    private lateinit var store: SshKeyStore
    private lateinit var listView: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var adapter: KeyAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ssh_keys)

        store = SshKeyStore(this)

        findViewById<MaterialToolbar>(R.id.toolbar)
            .setNavigationOnClickListener { finish() }

        emptyText = findViewById(R.id.emptyText)
        listView = findViewById(R.id.keysList)
        listView.layoutManager = LinearLayoutManager(this)
        adapter = KeyAdapter(
            onCopy = { copyToClipboard("Public key", it.publicKeyOpenSSH) },
            onView = { showPublicKeyDialog(it) },
            onDelete = { confirmDelete(it) }
        )
        listView.adapter = adapter

        findViewById<ExtendedFloatingActionButton>(R.id.generateKeyFab).setOnClickListener {
            promptGenerate()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val items = store.list().sortedByDescending { it.createdAt }
        adapter.submit(items)
        emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        listView.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun promptGenerate() {
        val nameBox = EditText(this).apply {
            hint = "Name (used as the key comment, e.g. ben@phone)"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val passBox = EditText(this).apply {
            hint = "Optional passphrase"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(nameBox)
            addView(passBox)
        }

        AlertDialog.Builder(this)
            .setTitle("Generate Ed25519 keypair")
            .setView(container)
            .setPositiveButton("Generate") { _, _ ->
                val name = nameBox.text.toString().trim()
                val pass = passBox.text.toString().takeIf { it.isNotEmpty() }
                if (name.isEmpty()) {
                    Toast.makeText(this, "Name required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                doGenerate(name, pass)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doGenerate(name: String, passphrase: String?) {
        Toast.makeText(this, "Generating…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val (privPem, pubLine) = SshKeyStore.generate(name, passphrase)
                val key = SshKeyStore.StoredKey(
                    name = name,
                    publicKeyOpenSSH = pubLine,
                    privateKeyPem = privPem,
                    passphraseHint = if (passphrase != null) "set" else null
                )
                store.add(key)
                runOnUiThread {
                    refresh()
                    showPublicKeyDialog(key)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Generate failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun showPublicKeyDialog(key: SshKeyStore.StoredKey) {
        val tv = TextView(this).apply {
            text = key.publicKeyOpenSSH
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, 0)
        }
        AlertDialog.Builder(this)
            .setTitle(key.name)
            .setMessage("Paste this into ~/.ssh/authorized_keys on the remote.")
            .setView(tv)
            .setPositiveButton("Copy") { _, _ ->
                copyToClipboard(key.name, key.publicKeyOpenSSH)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun confirmDelete(key: SshKeyStore.StoredKey) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${key.name}?")
            .setMessage("Profiles using this key will fail to authenticate until you swap them to a different key or to password auth.")
            .setPositiveButton("Delete") { _, _ ->
                store.delete(key.id)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun copyToClipboard(label: String, text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "Public key copied", Toast.LENGTH_SHORT).show()
    }

    private class KeyAdapter(
        val onCopy: (SshKeyStore.StoredKey) -> Unit,
        val onView: (SshKeyStore.StoredKey) -> Unit,
        val onDelete: (SshKeyStore.StoredKey) -> Unit
    ) : RecyclerView.Adapter<KeyAdapter.Holder>() {

        private val items = mutableListOf<SshKeyStore.StoredKey>()
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        fun submit(list: List<SshKeyStore.StoredKey>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.keyName)
            val meta: TextView = view.findViewById(R.id.keyMeta)
            val pub: TextView = view.findViewById(R.id.keyPublic)
            val copy: MaterialButton = view.findViewById(R.id.copyPublicButton)
            val menu: MaterialButton = view.findViewById(R.id.keyMenuButton)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_ssh_key, parent, false)
            return Holder(v)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val k = items[position]
            holder.name.text = k.name
            val protected = if (k.passphraseHint != null) " · passphrase" else ""
            holder.meta.text = "Ed25519 · ${dateFormat.format(Date(k.createdAt))}$protected"
            holder.pub.text = k.publicKeyOpenSSH
            holder.copy.setOnClickListener { onCopy(k) }
            holder.itemView.setOnClickListener { onView(k) }
            holder.menu.setOnClickListener { v ->
                PopupMenu(v.context, v).apply {
                    menu.add("View public key").setOnMenuItemClickListener { onView(k); true }
                    menu.add("Copy public key").setOnMenuItemClickListener { onCopy(k); true }
                    menu.add("Delete").setOnMenuItemClickListener { onDelete(k); true }
                }.show()
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
