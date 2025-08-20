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
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MonitoringService : Service() {

    private val channelId = "PolarisServiceChannel"

    // A custom CoroutineScope tied to the service's lifecycle for managing background tasks.
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Location
    private lateinit var fusedLocationClient: com.google.android.gms.location.FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var lastLocation: Location? = null

    // Telephony
    private lateinit var telephonyManager: TelephonyManager
    private lateinit var telephonyCallback: TelephonyCallback

    // Database (initialized lazily to ensure context is available)
    private val db by lazy { AppDatabase.getDatabase(this) }
    private val networkLogDao by lazy { db.networkLogDao() }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
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
        startSyncJob() // Start the coroutine-based sync job

        return START_STICKY
    }

    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 50)
            .setWaitForAccurateLocation(false)
            .setMinUpdateIntervalMillis(50)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations){
                    Log.d("MonitoringService", "New Location: Lat: ${location.latitude}, Lon: ${location.longitude}")
                    lastLocation = location
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
                    saveCellInfo(cellInfo)
                }
            }
            telephonyManager.registerTelephonyCallback(mainExecutor, telephonyCallback)
        }
    }

    private fun saveCellInfo(cellInfo: List<CellInfo>) {
        // Don't save logs until we have a location fix
        if (lastLocation == null) return

        for (info in cellInfo) {
            if (info.isRegistered) {
                val log = when (info) {
                    is CellInfoLte -> { // 4G Network
                        val cellIdentity = info.cellIdentity
                        val cellSignal = info.cellSignalStrength
                        NetworkLog(
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
                    is CellInfoWcdma -> { // 3G Network
                        val cellIdentity = info.cellIdentity
                        val cellSignal = info.cellSignalStrength
                        NetworkLog(
                            timestamp = System.currentTimeMillis(),
                            latitude = lastLocation?.latitude,
                            longitude = lastLocation?.longitude,
                            networkType = "WCDMA",
                            plmnId = "${cellIdentity.mccString}${cellIdentity.mncString}",
                            tac = cellIdentity.lac, // 3G uses LAC, we can map it to TAC
                            cellId = cellIdentity.cid,
                            rsrp = cellSignal.dbm, // 3G's RSCP is reported in dbm
                            rsrq = null // RSRQ is not available in 3G
                        )
                    }
                    is CellInfoGsm -> { // 2G Network
                        val cellIdentity = info.cellIdentity
                        val cellSignal = info.cellSignalStrength
                        NetworkLog(
                            timestamp = System.currentTimeMillis(),
                            latitude = lastLocation?.latitude,
                            longitude = lastLocation?.longitude,
                            networkType = "GSM",
                            plmnId = "${cellIdentity.mccString}${cellIdentity.mncString}",
                            tac = cellIdentity.lac, // 2G uses LAC
                            cellId = cellIdentity.cid,
                            rsrp = cellSignal.dbm, // 2G's RxLev is reported in dbm
                            rsrq = null // RSRQ is not available in 2G
                        )
                    }
                    else -> null // For other network types we don't handle
                }

                // If a log object was created, save it to the database.
                log?.let {
                    serviceScope.launch {
                        networkLogDao.insert(it)
                        Log.i("MonitoringService", "Successfully saved ${it.networkType} log to database.")
                    }
                }
            }
        }
    }

    private fun startSyncJob() {
        serviceScope.launch {
            while (isActive) { // This loop runs as long as the service scope is active
                syncLogsToServer()
                delay(60000L) // Wait for 60 seconds
            }
        }
    }

    private suspend fun syncLogsToServer() {
        val logs = networkLogDao.getAll()
        if (logs.isNotEmpty()) {
            val serializableLogs = logs.map {
                NetworkLogSerializable(it.timestamp, it.latitude, it.longitude, it.networkType, it.plmnId, it.tac, it.cellId, it.rsrp, it.rsrq)
            }

            val success = ApiClient.submitLogs(serializableLogs)
            if (success) {
                Log.i("MonitoringService", "Successfully sent ${logs.size} logs to server.")
                networkLogDao.deleteLogs(logs.map { it.id })
            } else {
                Log.e("MonitoringService", "Failed to send logs to server.")
            }
        } else {
            Log.d("MonitoringService", "No logs to sync.")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Cancel all coroutines started by this scope when the service is destroyed.
        serviceScope.cancel()

        fusedLocationClient.removeLocationUpdates(locationCallback)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && this::telephonyCallback.isInitialized) {
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