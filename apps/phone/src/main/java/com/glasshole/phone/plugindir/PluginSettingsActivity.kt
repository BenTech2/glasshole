package com.glasshole.phone.plugindir

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Typeface
import android.os.Bundle
import android.os.IBinder
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.glasshole.phone.R
import com.glasshole.phone.service.BridgeService
import org.json.JSONArray
import org.json.JSONObject

/**
 * Generic settings UI. Renders any plugin's schema (as shipped in its
 * `res/raw/plugin_schema.json`) as a simple form. Phone never hardcodes
 * plugin-specific UI anymore — the schema is the source of truth.
 *
 * Lifecycle:
 *   1. onCreate — bind BridgeService, read plugin-id extra.
 *   2. onResume — pull cached schema + config from PluginDirectory;
 *      request fresh copies if either is missing.
 *   3. Once both arrive, inflate the form.
 *   4. User taps Save → collect values → `writePluginConfig`.
 */
class PluginSettingsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PLUGIN_ID = "plugin_id"
    }

    private lateinit var pluginId: String
    private lateinit var fieldsContainer: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var headerName: TextView
    private lateinit var headerDescription: TextView

    private var bridgeService: BridgeService? = null
    private var bridgeBound = false

    private val directoryListener: () -> Unit = {
        runOnUiThread { renderIfReady() }
    }

    private val bridgeConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            bridgeService = (binder as BridgeService.LocalBinder).getService()
            bridgeBound = true
            ensureFetched()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            bridgeService = null
            bridgeBound = false
        }
    }

    // In-memory cache of current form state — keyed by setting key.
    // Values are the *current* values the user would save right now.
    private val currentValues = mutableMapOf<String, Any?>()

    // For visible_when / enabled_when re-evaluation — maps dependency
    // setting-key → list of rows that depend on it.
    private val dependents = mutableMapOf<String, MutableList<FieldRow>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_plugin_settings)
        pluginId = intent.getStringExtra(EXTRA_PLUGIN_ID) ?: run {
            Toast.makeText(this, "Missing plugin id", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        fieldsContainer = findViewById(R.id.pluginFieldsContainer)
        statusText = findViewById(R.id.pluginStatusText)
        headerName = findViewById(R.id.pluginHeaderName)
        headerDescription = findViewById(R.id.pluginHeaderDescription)

        val entry = PluginDirectory.entry(pluginId)
        headerName.text = entry?.name ?: pluginId
        headerDescription.text = entry?.description.orEmpty()
        headerDescription.visibility =
            if ((entry?.description ?: "").isNotEmpty()) View.VISIBLE else View.GONE

        findViewById<View>(R.id.pluginSaveButton).setOnClickListener { save() }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, BridgeService::class.java), bridgeConnection, Context.BIND_AUTO_CREATE)
        PluginDirectory.addListener(directoryListener)
    }

    override fun onStop() {
        super.onStop()
        if (bridgeBound) { unbindService(bridgeConnection); bridgeBound = false }
        PluginDirectory.removeListener(directoryListener)
    }

    private fun ensureFetched() {
        val bridge = bridgeService ?: return
        if (!bridge.isConnected) {
            statusText.text = "Glass not connected — connect to load settings."
            statusText.visibility = View.VISIBLE
            return
        }
        statusText.text = "Loading…"
        statusText.visibility = View.VISIBLE

        if (PluginDirectory.schemaFor(pluginId) == null) {
            bridge.requestPluginSchema(pluginId)
        }
        if (PluginDirectory.configFor(pluginId) == null) {
            bridge.requestPluginConfig(pluginId)
        }
        renderIfReady()
    }

    private fun renderIfReady() {
        val schema = PluginDirectory.schemaFor(pluginId) ?: return
        val config = PluginDirectory.configFor(pluginId) ?: return
        statusText.visibility = View.GONE
        renderForm(schema, config)
    }

    // ───────── Form rendering ─────────

    private data class FieldRow(
        val key: String,
        val rootView: View,
        val visibleWhen: Conditional?
    )

    private data class Conditional(val key: String, val eq: Any?)

    private fun renderForm(schema: JSONObject, config: JSONObject) {
        fieldsContainer.removeAllViews()
        currentValues.clear()
        dependents.clear()

        val settings = schema.optJSONArray("settings") ?: JSONArray()
        val rows = mutableListOf<FieldRow>()
        for (i in 0 until settings.length()) {
            val def = settings.optJSONObject(i) ?: continue
            val type = def.optString("type")
            val key = def.optString("key")
            val existingValue = if (config.has(key)) config.opt(key) else def.opt("default")

            val row = when (type) {
                "section" -> renderSection(def)
                "text" -> renderText(def, existingValue, password = false)
                "password" -> renderText(def, existingValue, password = true)
                "number" -> renderNumber(def, existingValue)
                "slider" -> renderSlider(def, existingValue)
                "radio" -> renderRadio(def, existingValue)
                "checkbox" -> renderCheckbox(def, existingValue)
                else -> continue
            } ?: continue

            fieldsContainer.addView(row.rootView)
            rows.add(row)

            row.visibleWhen?.let { cond ->
                dependents.getOrPut(cond.key) { mutableListOf() }.add(row)
            }
        }

        // Apply initial visibility pass.
        for (row in rows) {
            applyVisibility(row)
        }
    }

    private fun labelFor(def: JSONObject): String =
        def.optString("label").ifEmpty { def.optString("key") }

    private fun parseConditional(def: JSONObject): Conditional? {
        val node = def.optJSONObject("visible_when") ?: return null
        return Conditional(key = node.optString("key"), eq = node.opt("eq"))
    }

    private fun renderSection(def: JSONObject): FieldRow? {
        val tv = TextView(this).apply {
            text = labelFor(def)
            setTypeface(typeface, Typeface.BOLD)
            textSize = 15f
            val top = dp(16); val bottom = dp(8)
            setPadding(0, top, 0, bottom)
            setTextColor(0xFF888888.toInt())
        }
        return FieldRow(def.optString("key"), tv, parseConditional(def))
    }

    private fun renderText(def: JSONObject, value: Any?, password: Boolean): FieldRow {
        val key = def.optString("key")
        val wrapper = rowWrapper()
        val label = labelView(labelFor(def))
        val input = EditText(this).apply {
            setText(value?.toString().orEmpty())
            inputType = if (password)
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    currentValues[key] = s?.toString().orEmpty()
                    notifyDependents(key)
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        currentValues[key] = input.text.toString()
        wrapper.addView(label); wrapper.addView(input)
        return FieldRow(key, wrapper, parseConditional(def))
    }

    private fun renderNumber(def: JSONObject, value: Any?): FieldRow {
        val key = def.optString("key")
        val wrapper = rowWrapper()
        val label = labelView(labelFor(def))
        val input = EditText(this).apply {
            setText(value?.toString().orEmpty())
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    currentValues[key] = s?.toString().orEmpty().toLongOrNull() ?: 0L
                    notifyDependents(key)
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        currentValues[key] = input.text.toString().toLongOrNull() ?: 0L
        wrapper.addView(label); wrapper.addView(input)
        return FieldRow(key, wrapper, parseConditional(def))
    }

    private fun renderSlider(def: JSONObject, value: Any?): FieldRow {
        val key = def.optString("key")
        val min = def.optInt("min", 0)
        val max = def.optInt("max", 100)
        val step = def.optInt("step", 1).coerceAtLeast(1)
        val initial = (value as? Number)?.toInt() ?: def.optInt("default", min)

        val wrapper = rowWrapper()
        val rowHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val label = labelView(labelFor(def)).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val valueLabel = TextView(this).apply {
            text = initial.toString()
            textSize = 14f
        }
        rowHeader.addView(label); rowHeader.addView(valueLabel)

        val seek = SeekBar(this).apply {
            this.max = (max - min) / step
            progress = ((initial - min) / step).coerceAtLeast(0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = min + p * step
                    valueLabel.text = v.toString()
                    currentValues[key] = v
                    notifyDependents(key)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        currentValues[key] = initial
        wrapper.addView(rowHeader); wrapper.addView(seek)
        return FieldRow(key, wrapper, parseConditional(def))
    }

    private fun renderRadio(def: JSONObject, value: Any?): FieldRow {
        val key = def.optString("key")
        val options = def.optJSONArray("options") ?: JSONArray()
        val selected = value?.toString() ?: def.optString("default")

        val wrapper = rowWrapper()
        wrapper.addView(labelView(labelFor(def)))
        val group = RadioGroup(this)
        for (i in 0 until options.length()) {
            val opt = options.optJSONObject(i) ?: continue
            val v = opt.optString("value")
            val lbl = opt.optString("label", v)
            val rb = RadioButton(this).apply {
                text = lbl
                tag = v
                isChecked = v == selected
                id = View.generateViewId()
            }
            group.addView(rb)
        }
        group.setOnCheckedChangeListener { _, _ ->
            val selectedTag = (group.findViewById<RadioButton>(group.checkedRadioButtonId))?.tag
            currentValues[key] = selectedTag?.toString().orEmpty()
            notifyDependents(key)
        }
        currentValues[key] = selected
        wrapper.addView(group)
        return FieldRow(key, wrapper, parseConditional(def))
    }

    private fun renderCheckbox(def: JSONObject, value: Any?): FieldRow {
        val key = def.optString("key")
        val initial = when (value) {
            is Boolean -> value
            null -> def.optBoolean("default", false)
            else -> value.toString().toBoolean()
        }
        val sw = Switch(this).apply {
            text = labelFor(def)
            isChecked = initial
            setOnCheckedChangeListener(
                CompoundButton.OnCheckedChangeListener { _, isChecked ->
                    currentValues[key] = isChecked
                    notifyDependents(key)
                }
            )
        }
        currentValues[key] = initial
        val wrapper = rowWrapper()
        wrapper.addView(sw)
        return FieldRow(key, wrapper, parseConditional(def))
    }

    // ───────── Visibility rules ─────────

    private fun notifyDependents(changedKey: String) {
        val list = dependents[changedKey] ?: return
        for (row in list) applyVisibility(row)
    }

    private fun applyVisibility(row: FieldRow) {
        val cond = row.visibleWhen ?: run {
            row.rootView.visibility = View.VISIBLE; return
        }
        val actual = currentValues[cond.key]
        val matches = matches(actual, cond.eq)
        row.rootView.visibility = if (matches) View.VISIBLE else View.GONE
    }

    private fun matches(actual: Any?, expected: Any?): Boolean {
        if (actual == null && expected == null) return true
        if (actual == null || expected == null) return false
        return actual.toString() == expected.toString()
    }

    // ───────── Save ─────────

    private fun save() {
        val bridge = bridgeService
        if (bridge == null || !bridge.isConnected) {
            Toast.makeText(this, "Glass not connected", Toast.LENGTH_SHORT).show()
            return
        }
        val out = JSONObject()
        for ((k, v) in currentValues) {
            try { out.put(k, v) } catch (_: Exception) {}
        }
        val ok = bridge.writePluginConfig(pluginId, out.toString())
        Toast.makeText(
            this,
            if (ok) "Saved — glass updated" else "Save failed",
            Toast.LENGTH_SHORT
        ).show()
        if (ok) finish()
    }

    // ───────── UI helpers ─────────

    private fun rowWrapper(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16) }
    }

    private fun labelView(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(0xFFAAAAAA.toInt())
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()
}
