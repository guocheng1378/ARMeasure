package com.armeasure.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.armeasure.app.databinding.ActivityHistoryBinding
import com.armeasure.app.databinding.ItemHistoryBinding
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.*

class HistoryActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHistoryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        val historyPrefs = getSharedPreferences("armeasure_history", MODE_PRIVATE)
        val raw = historyPrefs.getString("data", "[]") ?: "[]"
        val entries = mutableListOf<HistoryEntry>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                entries.add(HistoryEntry(o.getLong("t"), o.getString("m"), o.getString("r"), o.getString("u")))
            }
        } catch (_: Exception) {}

        binding.rvHistory.layoutManager = LinearLayoutManager(this)
        binding.rvHistory.adapter = HistoryAdapter(entries)

        binding.btnExport.setOnClickListener {
            val text = entries.joinToString("\n") { e ->
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(e.timestamp))
                "$time [${e.mode}] ${e.result}"
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "AR测距记录\n\n$text")
                putExtra(Intent.EXTRA_SUBJECT, "AR测距记录")
            }
            startActivity(Intent.createChooser(intent, "导出记录"))
        }
    }

    data class HistoryEntry(val timestamp: Long, val mode: String, val result: String, val unit: String)

    class HistoryAdapter(private val entries: List<HistoryEntry>) : RecyclerView.Adapter<HistoryAdapter.VH>() {
        class VH(val binding: ItemHistoryBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val binding = ItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(binding)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val e = entries[position]
            holder.binding.tvResult.text = e.result
            holder.binding.tvMode.text = e.mode
            holder.binding.tvTime.text = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(e.timestamp))
            holder.binding.tvModeIcon.text = when (e.mode) {
                "单点" -> "⊙"
                "两点", "测距" -> "↔"
                "面积" -> "◇"
                "高度" -> "↕"
                "角度" -> "∠"
                else -> "·"
            }
        }

        override fun getItemCount() = entries.size
    }
}
