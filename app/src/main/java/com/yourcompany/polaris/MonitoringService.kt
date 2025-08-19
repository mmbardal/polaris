package com.yourcompany.polaris

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import java.util.concurrent.Executor

class MonitoringService : Service() {

    private val channelId = "PolarisServiceChannel"

    // Location variables
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    // Telephony variables
    private lateinit var telephonyManager: TelephonyManager
    private lateinit var telephonyCallback: TelephonyCallback

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // Initialize clients
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("MonitoringService", "Service is starting...")

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Polaris Monitoring")
            .setContentText("Data collection is active.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()

        startForeground(1, notification)

        startLocationUpdates()
        startTelephonyUpdates()

        return START_STICKY
    }

    private fun startLocationUpdates() {
        // ... (this function remains unchanged)
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
            .setWaitForAccurateLocation(false)
            .setMinUpdateIntervalMillis(3000)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations){
                    Log.d("MonitoringService", "New Location: Lat: ${location.latitude}, Lon: ${location.longitude}")
                }
            }
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e("MonitoringService", "Location permission not granted, stopping service.")
            stopSelf()
            return
        }

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
    }

    private fun startTelephonyUpdates() {
        // This Executor will run the callback
        val executor = mainExecutor

        // Create the callback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback = object : TelephonyCallback(), TelephonyCallback.CellInfoListener {
                override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) {
                    Log.d("MonitoringService", "Cell Info Changed:")
                    logCellInfo(cellInfo)
                }
            }
            telephonyManager.registerTelephonyCallback(executor, telephonyCallback)
        }
    }

    private fun logCellInfo(cellInfo: List<CellInfo>) {
        for (info in cellInfo) {
            if (info.isRegistered) { // We only care about the cell the phone is currently connected to
                when (info) {
                    is CellInfoLte -> { // 4G
                        val cellIdentity = info.cellIdentity
                        val cellSignal = info.cellSignalStrength
                        Log.i("MonitoringService", "Type: LTE (4G)")
                        Log.i("MonitoringService", "   PLMN-Id: ${cellIdentity.mccString}${cellIdentity.mncString}, TAC: ${cellIdentity.tac}, Cell ID: ${cellIdentity.ci}")
                        Log.i("MonitoringService", "   RSRP: ${cellSignal.rsrp} dBm, RSRQ: ${cellSignal.rsrq} dB")
                    }
                    is CellInfoWcdma -> { // 3G
                        val cellIdentity = info.cellIdentity
                        val cellSignal = info.cellSignalStrength
                        Log.i("MonitoringService", "Type: WCDMA (3G)")
                        Log.i("MonitoringService", "   PLMN-Id: ${cellIdentity.mccString}${cellIdentity.mncString}, LAC: ${cellIdentity.lac}, Cell ID: ${cellIdentity.cid}")
                        Log.i("MonitoringService", "   RSCP: ${cellSignal.dbm} dBm")
                    }
                    is CellInfoGsm -> { // 2G
                        val cellIdentity = info.cellIdentity
                        val cellSignal = info.cellSignalStrength
                        Log.i("MonitoringService", "Type: GSM (2G)")
                        Log.i("MonitoringService", "   PLMN-Id: ${cellIdentity.mccString}${cellIdentity.mncString}, LAC: ${cellIdentity.lac}, Cell ID: ${cellIdentity.cid}")
                        Log.i("MonitoringService", "   RxLev: ${cellSignal.dbm} dBm")
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyManager.unregisterTelephonyCallback(telephonyCallback)
        }
        Log.d("MonitoringService", "Service is being destroyed. All updates stopped.")
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        // ... (this function remains unchanged)
        val serviceChannel = NotificationChannel(
            channelId,
            "Polaris Service Channel",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(serviceChannel)
    }
}