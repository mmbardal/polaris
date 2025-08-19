package com.yourcompany.polaris // Make sure this matches your package name

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    // This variable will keep track of whether the service is currently running
    private var isServiceRunning = false

    // Declare UI elements
    private lateinit var statusTextView: TextView
    private lateinit var toggleServiceButton: Button

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.entries.all { it.value }
            if (allGranted) {
                // Permissions are granted, now start the service
                startMonitoringService()
            } else {
                Toast.makeText(this, "Permissions are required to run the app.", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI elements
        statusTextView = findViewById(R.id.statusTextView)
        toggleServiceButton = findViewById(R.id.toggleServiceButton)

        toggleServiceButton.setOnClickListener {
            if (isServiceRunning) {
                stopMonitoringService()
            } else {
                // When the button is clicked, check for permissions first
                checkAndRequestPermissions()
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val requiredPermissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.SEND_SMS
        ).apply {
            // Add background location for Android 10+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
            // ADD THIS BLOCK for notification permission on Android 13+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()

        val allPermissionsGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allPermissionsGranted) {
            startMonitoringService()
        } else {
            requestPermissionLauncher.launch(requiredPermissions)
        }
    }

    private fun startMonitoringService() {
        val serviceIntent = Intent(this, MonitoringService::class.java)
        // Use startForegroundService for Android 8 (Oreo) and above
        ContextCompat.startForegroundService(this, serviceIntent)

        // Update UI
        isServiceRunning = true
        statusTextView.text = "Service is Running"
        toggleServiceButton.text = "Stop Monitoring"
    }

    private fun stopMonitoringService() {
        val serviceIntent = Intent(this, MonitoringService::class.java)
        stopService(serviceIntent)

        // Update UI
        isServiceRunning = false
        statusTextView.text = "Service is Stopped"
        toggleServiceButton.text = "Start Monitoring"
    }
}