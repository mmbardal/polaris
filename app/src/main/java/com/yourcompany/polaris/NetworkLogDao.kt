package com.yourcompany.polaris

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface NetworkLogDao {
    @Insert
    suspend fun insert(log: NetworkLog)

    @Query("SELECT * FROM network_logs ORDER BY timestamp DESC")
    suspend fun getAll(): List<NetworkLog>
}