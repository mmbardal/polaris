package com.yourcompany.polaris

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable // Import this

// In NetworkLog.kt

@Serializable // Make sure this is still here
@Entity(tableName = "network_logs")
data class NetworkLog(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val timestamp: Long,
    val latitude: Double?,
    val longitude: Double?,
    val networkType: String?,
    val plmnId: String?,
    val tac: Int?,      // Will also store LAC for 2G/3G
    val cellId: Int?,

    // LTE specific
    val rsrp: Int?,
    val rsrq: Int?,

    // 3G specific
    val rscp: Int?,
    val ecno: Int?,     // Energy per chip to noise power spectral density

    // 2G specific
    val rxlev: Int?,    // We'll use this instead of re-purposing rsrp

    // All types
    val arfcn: Int?,    // Absolute Radio Frequency Channel Number
    val band: String?   // Frequency Band (Primarily for LTE)
)