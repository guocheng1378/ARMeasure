package com.armeasure.app

import android.content.Intent
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
            // Return to main activity with calibration intent.
            // 必须新建回传 Intent：直接复用本页启动 Intent 会把 action 混入原始启动参数，
            // 导致重复触发校准（原 bug：setResult(RESULT_OK, intent.putExtra(...))）
            setResult(RESULT_OK, Intent().putExtra("action", "calibrate"))
            finish()
        }
        // 重新显示教程：清除已读标记，回到主界面 onResume 时会再次弹出
        binding.btnShowTutorial.setOnClickListener {
            getSharedPreferences(AppConstants.TUTORIAL_PREF, MODE_PRIVATE)
                .edit().putBoolean(AppConstants.TUTORIAL_SHOWN_KEY, false).apply()
            Toast.makeText(this, R.string.settings_tutorial_reset_done, Toast.LENGTH_SHORT).show()
        }
        binding.btnClearHistory.setOnClickListener {
            getSharedPreferences("armeasure_history", MODE_PRIVATE).edit().clear().apply()
            Toast.makeText(this, "历史记录已清空", Toast.LENGTH_SHORT).show()
        }
    }
}
