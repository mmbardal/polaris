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
import androidx.annotation.RequiresApi
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
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Clients
    private lateinit var fusedLocationClient: com.google.android.gms.location.FusedLocationProviderClient
    private lateinit var telephonyManager: TelephonyManager

    // Callbacks
    private lateinit var locationCallback: LocationCallback
    private lateinit var telephonyCallback: TelephonyCallback

    // State
    private var lastLocation: Location? = null

    // Database
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
        val notification = createNotification()
        startForeground(1, notification)

        startLocationUpdates()
        startTelephonyUpdates()
        startSyncJob()

        return START_STICKY
    }

    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
            .setWaitForAccurateLocation(false)
            .setMinUpdateIntervalMillis(3000)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let {
                    Log.d("MonitoringService", "New Location: Lat: ${it.latitude}, Lon: ${it.longitude}")
                    lastLocation = it
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

    @RequiresApi(Build.VERSION_CODES.R)
    private fun saveCellInfo(cellInfo: List<CellInfo>) {
        if (lastLocation == null) return

        for (info in cellInfo) {
            if (info.isRegistered) {
                val log = when (info) {
                    is CellInfoLte -> {
                        val cellIdentity = info.cellIdentity
                        val cellSignal = info.cellSignalStrength

                        // Use a try-catch block for maximum safety and to resolve the IDE error.
                        val bandValue = try {
                            // This is safe because your minSdk is 28, but we use a try-catch to be certain.
                            cellIdentity.bands.toString()
                        } catch (e: Exception) {
                            null // If for any reason it fails, we default to null.
                        }

                        NetworkLog(
                            timestamp = System.currentTimeMillis(),
                            latitude = lastLocation?.latitude, longitude = lastLocation?.longitude,
                            networkType = "LTE",
                            plmnId = "${cellIdentity.mccString}${cellIdentity.mncString}",
                            tac = cellIdentity.tac, cellId = cellIdentity.ci,
                            rsrp = cellSignal.rsrp, rsrq = cellSignal.rsrq,
                            rscp = null, ecno = null, rxlev = null,
                            arfcn = cellIdentity.earfcn,
                            band = bandValue
                        )
                    }
                    is CellInfoWcdma -> {
                        val cellIdentity = info.cellIdentity
                        val cellSignal = info.cellSignalStrength
                        NetworkLog(
                            timestamp = System.currentTimeMillis(),
                            latitude = lastLocation?.latitude, longitude = lastLocation?.longitude,
                            networkType = "WCDMA",
                            plmnId = "${cellIdentity.mccString}${cellIdentity.mncString}",
                            tac = cellIdentity.lac, cellId = cellIdentity.cid,
                            rsrp = null, rsrq = null,
                            rscp = cellSignal.dbm,
                            ecno =  cellSignal.ecNo ,
                            rxlev = null,
                            arfcn = cellIdentity.uarfcn,
                            band = null
                        )
                    }
                    is CellInfoGsm -> {
                        val cellIdentity = info.cellIdentity
                        val cellSignal = info.cellSignalStrength
                        NetworkLog(
                            timestamp = System.currentTimeMillis(),
                            latitude = lastLocation?.latitude, longitude = lastLocation?.longitude,
                            networkType = "GSM",
                            plmnId = "${cellIdentity.mccString}${cellIdentity.mncString}",
                            tac = cellIdentity.lac, cellId = cellIdentity.cid,
                            rsrp = null, rsrq = null,
                            rscp = null, ecno = null,
                            rxlev = cellSignal.dbm,
                            arfcn = cellIdentity.arfcn,
                            band = null
                        )
                    }
                    else -> null
                }

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
            while (isActive) {
                syncLogsToServer()
                delay(10000L)
            }
        }
    }

    private suspend fun syncLogsToServer() {
        val logs = networkLogDao.getAll()
        if (logs.isNotEmpty()) {
            val serializableLogs = logs.map {
                NetworkLogSerializable(it.timestamp, it.latitude, it.longitude, it.networkType, it.plmnId, it.tac, it.cellId, it.rsrp, it.rsrq, it.rscp, it.ecno, it.rxlev, it.arfcn, it.band)
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
        serviceScope.cancel()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && this::telephonyCallback.isInitialized) {
            telephonyManager.unregisterTelephonyCallback(telephonyCallback)
        }
        Log.d("MonitoringService", "Service is being destroyed. All updates stopped.")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Polaris Monitoring")
            .setContentText("Data collection is active.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            channelId, "Polaris Service Channel", NotificationManager.IMPORTANCE_DEFAULT
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(serviceChannel)
    }
}