package com.pothole.v3

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class SettingsActivity : AppCompatActivity() {
    private lateinit var config: ProfessionalConfig
    private lateinit var locationCheck: CheckBox

    private val requestLocation = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        locationCheck.isChecked = granted
        config.setLocationEnabled(granted)
        if (granted) {
            Toast.makeText(this, "Ajustes guardados • ubicación activada", Toast.LENGTH_SHORT).show()
            finish()
        } else {
            Toast.makeText(this, "Ubicación desactivada", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        config = ProfessionalConfig(applicationContext)

        val modeSpinner = findViewById<Spinner>(R.id.modeSpinner)
        val modes = PerformanceMode.values().toList()
        modeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            modes.map { it.label }
        )
        modeSpinner.setSelection(modes.indexOf(config.performanceMode()).coerceAtLeast(0))

        locationCheck = findViewById(R.id.locationCheck)
        locationCheck.isChecked = config.locationEnabled()
        val diagnosticsCheck = findViewById<CheckBox>(R.id.diagnosticsCheck)
        diagnosticsCheck.isChecked = config.diagnosticsEnabled()

        val cal = config.calibration()
        findViewById<TextView>(R.id.settingsSummary).text =
            "Calibración: horizonte ${(cal.horizonFraction * 100).toInt()}% • centro ${(cal.roadCenterFraction * 100).toInt()}% • altura ${cal.phoneHeightCm} cm"

        findViewById<Button>(R.id.settingsSaveButton).setOnClickListener {
            val selected = modes.getOrElse(modeSpinner.selectedItemPosition) { PerformanceMode.AUTO }
            config.savePerformanceMode(selected)
            config.setDiagnosticsEnabled(diagnosticsCheck.isChecked)

            if (locationCheck.isChecked) {
                val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (!fine && !coarse) {
                    requestLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    return@setOnClickListener
                }
            }
            config.setLocationEnabled(locationCheck.isChecked)
            Toast.makeText(this, "Ajustes guardados", Toast.LENGTH_SHORT).show()
            finish()
        }

        findViewById<Button>(R.id.settingsCancelButton).setOnClickListener { finish() }
    }
}
