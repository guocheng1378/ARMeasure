package com.armeasure.app

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.armeasure.app.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences("armeasure_settings", MODE_PRIVATE)

        // Load current settings
        when (prefs.getString("unit", "cm")) {
            "cm" -> binding.rbCm.isChecked = true
            "inch" -> binding.rbInch.isChecked = true
            "m" -> binding.rbM.isChecked = true
        }
        binding.swUncertainty.isChecked = prefs.getBoolean("show_uncertainty", true)
        binding.swHaptic.isChecked = prefs.getBoolean("haptic", true)
        binding.swDepth.isChecked = prefs.getBoolean("depth_enabled", false)

        // Save on change
        binding.rgUnit.setOnCheckedChangeListener { _, checkedId ->
            val unit = when (checkedId) {
                R.id.rbCm -> "cm"
                R.id.rbInch -> "inch"
                R.id.rbM -> "m"
                else -> "cm"
            }
            prefs.edit().putString("unit", unit).apply()
        }
        binding.swUncertainty.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("show_uncertainty", checked).apply()
        }
        binding.swHaptic.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("haptic", checked).apply()
        }
        binding.swDepth.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("depth_enabled", checked).apply()
        }

        binding.btnCalibrate.setOnClickListener {
            // Return to main activity with calibration intent
            setResult(RESULT_OK, intent.putExtra("action", "calibrate"))
            finish()
        }
        binding.btnClearHistory.setOnClickListener {
            getSharedPreferences("armeasure_history", MODE_PRIVATE).edit().clear().apply()
            Toast.makeText(this, "历史记录已清空", Toast.LENGTH_SHORT).show()
        }
    }
}
