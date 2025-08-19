package com.yourcompany.polaris

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
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
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class MonitoringService : Service() {

    private val channelId = "PolarisServiceChannel"

    // Location
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var lastLocation: Location? = null

    // Telephony
    private lateinit var telephonyManager: TelephonyManager
    private lateinit var telephonyCallback: TelephonyCallback

    // Database
    private lateinit var db: AppDatabase
    private lateinit var networkLogDao: NetworkLogDao

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // Initialize clients and DB
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        db = AppDatabase.getDatabase(this)
        networkLogDao = db.networkLogDao()
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
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
            .setWaitForAccurateLocation(false)
            .setMinUpdateIntervalMillis(3000)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations){
                    Log.d("MonitoringService", "New Location: Lat: ${location.latitude}, Lon: ${location.longitude}")
                    lastLocation = location // Store the most recent location
                }
            }
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
    }

    private fun startTelephonyUpdates() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback = object : TelephonyCallback(), TelephonyCallback.CellInfoListener {
                override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) {
                    Log.d("MonitoringService", "Cell Info Changed:")
                    saveCellInfo(cellInfo) // Call the new save function
                }
            }
            telephonyManager.registerTelephonyCallback(mainExecutor, telephonyCallback)
        }
    }

    private fun saveCellInfo(cellInfo: List<CellInfo>) {
        for (info in cellInfo) {
            if (info.isRegistered) {
                var log: NetworkLog? = null
                when (info) {
                    is CellInfoLte -> {
                        val cellIdentity = info.cellIdentity
                        val cellSignal = info.cellSignalStrength
                        log = NetworkLog(
                            timestamp = System.currentTimeMillis(),
                            latitude = lastLocation?.latitude,
                            longitude = lastLocation?.longitude,
                            networkType = "LTE",
                            plmnId = "${cellIdentity.mccString}${cellIdentity.mncString}",
                            tac = cellIdentity.tac,
                            cellId = cellIdentity.ci,
                            rsrp = cellSignal.rsrp,
                            rsrq = cellSignal.rsrq
                        )
                    }
                    // Add cases for WCDMA and GSM here if needed
                }

                log?.let {
                    GlobalScope.launch {
                        networkLogDao.insert(it)
                        Log.i("MonitoringService", "Successfully saved log to database.")
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

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            channelId, "Polaris Service Channel", NotificationManager.IMPORTANCE_DEFAULT
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(serviceChannel)
    }
}