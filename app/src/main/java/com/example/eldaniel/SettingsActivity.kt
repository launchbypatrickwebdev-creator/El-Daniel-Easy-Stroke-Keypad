package com.example.eldaniel

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.edit
import androidx.core.net.toUri

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // --- 1. CORE KEYBOARD SETTINGS ---
        val prefs = getSharedPreferences("ElDanielPrefs", Context.MODE_PRIVATE)

        val speedSeekBar = findViewById<SeekBar>(R.id.speedSeekBar)
        val speedValueText = findViewById<TextView>(R.id.speedValueText)
        val vibSwitch = findViewById<SwitchCompat>(R.id.vibrationSwitch)

        // Load saved delay or default to 400ms
        val savedDelay = prefs.getLong("commit_delay", 400L)
        speedSeekBar.progress = savedDelay.toInt()
        speedValueText.text = getString(R.string.speed_display, savedDelay.toInt())

        speedSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(p0: SeekBar?, progress: Int, p2: Boolean) {
                val finalValue = if (progress < 100) 100 else progress
                speedValueText.text = getString(R.string.speed_display, finalValue)
                prefs.edit {
                    putLong("commit_delay", finalValue.toLong())
                }
            }
            override fun onStartTrackingTouch(p0: SeekBar?) {}
            override fun onStopTrackingTouch(p0: SeekBar?) {}
        })

        // Handle Vibration Toggle
        vibSwitch.isChecked = prefs.getBoolean("haptic_enabled", true)
        vibSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit {
                putBoolean("haptic_enabled", isChecked)
            }
        }

        // --- 2. STEWARDSHIP & DONATION LOGIC ---
        val donateButton = findViewById<Button>(R.id.btn_donate)

        donateButton.setOnClickListener {
            // Options that respect the user's preference for payment method
            val options = arrayOf(
                "Paystack (Local/International Cards)",
                "Flutterwave (USSD/Bank/Cards)"
            )

            val builder = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            builder.setTitle("Support the Mission")
            builder.setItems(options) { _, which ->
                val url = when (which) {
                    0 -> "https://paystack.shop/pay/launchbypatrick_mission"
                    1 -> "https://flutterwave.com/pay/echolevel-sentinel"
                    else -> ""
                }

                if (url.isNotEmpty()) {
                    val intent = Intent(Intent.ACTION_VIEW)
                    intent.data = url.toUri()
                    startActivity(intent)
                }
            }
            // Add a "Cancel" option to ensure the user never feels trapped
            builder.setNegativeButton("Maybe Later", null)
            builder.show()
        }
    }
}