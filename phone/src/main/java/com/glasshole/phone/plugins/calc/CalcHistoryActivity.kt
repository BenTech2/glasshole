package com.glasshole.phone.plugins.calc

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.glasshole.phone.R
import org.json.JSONArray

class CalcHistoryActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var emptyText: TextView
    private lateinit var clearButton: Button
    private lateinit var adapter: ArrayAdapter<String>
    private val items = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calc_history)

        listView = findViewById(R.id.history_list)
        emptyText = findViewById(R.id.empty_text)
        clearButton = findViewById(R.id.clear_button)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, items)
        listView.adapter = adapter

        clearButton.setOnClickListener {
            getSharedPreferences(CalcPlugin.PREFS_NAME, MODE_PRIVATE)
                .edit().putString(CalcPlugin.KEY_HISTORY, "[]").apply()
            loadHistory()
        }
    }

    override fun onResume() {
        super.onResume()
        loadHistory()
    }

    private fun loadHistory() {
        items.clear()

        val prefs = getSharedPreferences(CalcPlugin.PREFS_NAME, MODE_PRIVATE)
        val historyStr = prefs.getString(CalcPlugin.KEY_HISTORY, "[]") ?: "[]"

        try {
            val history = JSONArray(historyStr)
            for (i in 0 until history.length()) {
                val entry = history.getJSONObject(i)
                val expr = entry.getString("expr")
                val result = entry.getString("result")
                items.add("$expr = $result")
            }
        } catch (_: Exception) {}

        adapter.notifyDataSetChanged()

        if (items.isEmpty()) {
            listView.visibility = View.GONE
            emptyText.visibility = View.VISIBLE
            clearButton.visibility = View.GONE
        } else {
            listView.visibility = View.VISIBLE
            emptyText.visibility = View.GONE
            clearButton.visibility = View.VISIBLE
        }
    }
}
