package com.yourcompany.polaris

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "network_logs")
data class NetworkLog(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val timestamp: Long,
    val latitude: Double?,
    val longitude: Double?,
    val networkType: String?,
    val plmnId: String?,
    val tac: Int?,
    val cellId: Int?,
    val rsrp: Int?,
    val rsrq: Int?
)