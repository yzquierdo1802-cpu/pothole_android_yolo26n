package com.pothole.v3

import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** Calibración sencilla para adaptar ROI/perspectiva a la instalación del teléfono. */
class CalibrationActivity : AppCompatActivity() {
    private lateinit var config: ProfessionalConfig
    private lateinit var summary: TextView
    private lateinit var horizon: SeekBar
    private lateinit var center: SeekBar
    private lateinit var height: SeekBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calibration)
        config = ProfessionalConfig(applicationContext)

        summary = findViewById(R.id.calibrationSummary)
        horizon = findViewById(R.id.horizonSeek)
        center = findViewById(R.id.centerSeek)
        height = findViewById(R.id.heightSeek)

        val current = config.calibration()
        horizon.progress = ((current.horizonFraction - 0.15f) * 100f).toInt().coerceIn(0, 45)
        center.progress = ((current.roadCenterFraction - 0.30f) * 100f).toInt().coerceIn(0, 40)
        height.progress = (current.phoneHeightCm - 60).coerceIn(0, 160)
        updateSummary()

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = updateSummary()
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        }
        horizon.setOnSeekBarChangeListener(listener)
        center.setOnSeekBarChangeListener(listener)
        height.setOnSeekBarChangeListener(listener)

        findViewById<Button>(R.id.calibrationSaveButton).setOnClickListener {
            config.saveCalibration(value())
            Toast.makeText(this, "Calibración guardada", Toast.LENGTH_SHORT).show()
            finish()
        }
        findViewById<Button>(R.id.calibrationResetButton).setOnClickListener {
            config.saveCalibration(RoadCalibration())
            val d = RoadCalibration()
            horizon.progress = ((d.horizonFraction - 0.15f) * 100f).toInt()
            center.progress = ((d.roadCenterFraction - 0.30f) * 100f).toInt()
            height.progress = d.phoneHeightCm - 60
            updateSummary()
        }
        findViewById<Button>(R.id.calibrationCancelButton).setOnClickListener { finish() }
    }

    private fun value() = RoadCalibration(
        horizonFraction = 0.15f + horizon.progress / 100f,
        roadCenterFraction = 0.30f + center.progress / 100f,
        phoneHeightCm = 60 + height.progress
    )

    private fun updateSummary() {
        val v = value()
        summary.text = "Horizonte ${(v.horizonFraction * 100).toInt()}% • Centro ${(v.roadCenterFraction * 100).toInt()}% • Altura ${v.phoneHeightCm} cm"
    }
}
