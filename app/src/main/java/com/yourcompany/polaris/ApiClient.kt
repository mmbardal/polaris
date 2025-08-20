package com.yourcompany.polaris

import android.util.Log
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable

/**
 * A data class that matches the top-level structure of the server's expected JSON.
 * e.g., { "logs": [...] }
 */
@Serializable
data class LogPayload(val logs: List<NetworkLogSerializable>)

/**
 * A serializable version of our NetworkLog entity.
 * This class is used for sending data over the network.
 */
@Serializable
data class NetworkLogSerializable(
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

object ApiClient {
    /**
     * The Ktor HttpClient instance.
     * It's configured with:
     * - CIO engine: A flexible, coroutine-based engine.
     * - ContentNegotiation: To automatically handle JSON serialization/deserialization.
     * - Logging: To print detailed request/response information to Logcat for debugging.
     */
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json()
        }

        install(Logging) {
            logger = object : Logger {
                override fun log(message: String) {
                    // Log all Ktor messages with a specific tag for easy filtering.
                    Log.v("KtorLogger", message)
                }
            }
            // LogLevel.ALL ensures we see headers, body, and other details.
            level = LogLevel.ALL
        }
    }

    // !! IMPORTANT !!
    // Replace this with the actual IP address of the computer running your server.
    private const val SERVER_IP = "192.168.246.3" // Example IP

    private const val SUBMIT_URL = "http://$SERVER_IP:3000/api/v1/panel/submitLogs"

    /**
     * Submits a list of network logs to the server.
     * @param logs The list of logs to send.
     * @return `true` if the request was successful (HTTP 2xx response), `false` otherwise.
     */
    suspend fun submitLogs(logs: List<NetworkLogSerializable>): Boolean {
        return try {
            val payload = LogPayload(logs = logs)
            client.post(SUBMIT_URL) {
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
            // If the request doesn't throw an exception, it was successful.
            true
        } catch (e: Exception) {
            // Log any exceptions that occur during the network request.
            Log.e("ApiClient", "Failed to submit logs: ${e.message}")
            e.printStackTrace()
            false
        }
    }
}