package com.glasshole.phone.plugins.ssh

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.glasshole.phone.R
import com.glasshole.phone.service.BridgeService
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import org.json.JSONObject

/**
 * Top-level "SSH" page on the phone. Lists every saved profile, lets
 * the user add / edit / delete, and pushes the snapshot over BT to
 * the glass on every change. Quick-Connect on a row sends an OPEN
 * envelope to the glass plugin which foregrounds the terminal screen
 * and dials the remote.
 *
 * The phone is the source of truth for profiles; the glass keeps a
 * cache (see ProfileStore in plugin-ssh-glass) so on-glass picks work
 * even when the phone has wandered off.
 */
class SshActivity : AppCompatActivity() {

    private lateinit var store: SshProfileStore
    private lateinit var listView: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var adapter: ProfileAdapter

    private var bridge: BridgeService? = null
    private var bridgeBound = false

    private val bridgeConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, b: IBinder?) {
            bridge = (b as BridgeService.LocalBinder).getService()
            bridgeBound = true
            // Always re-sync on bind so a fresh glass connection picks
            // up whatever the phone has, even if nothing was edited.
            syncToGlass()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            bridge = null
            bridgeBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ssh)

        store = SshProfileStore(this)

        findViewById<MaterialToolbar>(R.id.toolbar)
            .setNavigationOnClickListener { finish() }

        emptyText = findViewById(R.id.emptyText)
        listView = findViewById(R.id.profilesList)
        listView.layoutManager = LinearLayoutManager(this)
        adapter = ProfileAdapter(
            onConnect = { p -> quickConnect(p) },
            onEdit = { p -> startActivity(SshProfileEditorActivity.intent(this, p.id)) },
            onDelete = { p ->
                store.delete(p.id)
                refresh()
                syncToGlass()
                Toast.makeText(this, "Deleted ${p.name}", Toast.LENGTH_SHORT).show()
            }
        )
        listView.adapter = adapter

        findViewById<ExtendedFloatingActionButton>(R.id.addProfileFab).setOnClickListener {
            startActivity(SshProfileEditorActivity.intent(this, null))
        }
        findViewById<MaterialButton>(R.id.manageKeysButton).setOnClickListener {
            startActivity(Intent(this, SshKeyManagerActivity::class.java))
        }

        bindService(
            Intent(this, BridgeService::class.java),
            bridgeConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onResume() {
        super.onResume()
        refresh()
        if (bridgeBound) syncToGlass()
    }

    override fun onDestroy() {
        if (bridgeBound) {
            unbindService(bridgeConnection)
            bridgeBound = false
        }
        super.onDestroy()
    }

    private fun refresh() {
        val items = store.list()
        adapter.submit(items)
        emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        listView.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun syncToGlass() {
        val b = bridge ?: return
        if (!b.isConnected) return
        b.sendPluginMessage("ssh", "SET_PROFILES", store.snapshotJson())
    }

    private fun quickConnect(profile: SshProfileStore.Profile) {
        val b = bridge
        if (b == null || !b.isConnected) {
            Toast.makeText(this, "Glass not connected", Toast.LENGTH_SHORT).show()
            return
        }
        // Resync first so the glass always has the latest profile bytes
        // before we ask it to dial — guards against a "you edited and
        // didn't connect since" race.
        b.sendPluginMessage("ssh", "SET_PROFILES", store.snapshotJson())
        val payload = JSONObject().put("id", profile.id).toString()
        val ok = b.sendPluginMessage("ssh", "OPEN", payload)
        Toast.makeText(
            this,
            if (ok) "Opening ${profile.name} on glass…" else "Send failed",
            Toast.LENGTH_SHORT
        ).show()
    }

    private class ProfileAdapter(
        val onConnect: (SshProfileStore.Profile) -> Unit,
        val onEdit: (SshProfileStore.Profile) -> Unit,
        val onDelete: (SshProfileStore.Profile) -> Unit
    ) : RecyclerView.Adapter<ProfileAdapter.Holder>() {

        private val items = mutableListOf<SshProfileStore.Profile>()

        fun submit(list: List<SshProfileStore.Profile>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.profileName)
            val sub: TextView = view.findViewById(R.id.profileSub)
            val connect: MaterialButton = view.findViewById(R.id.quickConnectButton)
            val menu: MaterialButton = view.findViewById(R.id.profileMenuButton)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_ssh_profile, parent, false)
            return Holder(v)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val p = items[position]
            holder.name.text = p.name
            val authLabel = if (p.authMode == SshProfileStore.AuthMode.KEY) "key" else "password"
            holder.sub.text = "${p.user}@${p.host}:${p.port} · $authLabel"
            holder.connect.setOnClickListener { onConnect(p) }
            holder.itemView.setOnClickListener { onConnect(p) }
            holder.menu.setOnClickListener { v ->
                PopupMenu(v.context, v).apply {
                    menu.add("Edit").setOnMenuItemClickListener { onEdit(p); true }
                    menu.add("Delete").setOnMenuItemClickListener { onDelete(p); true }
                }.show()
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
