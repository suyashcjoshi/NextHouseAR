/* Copyright 2026 Suyash Joshi */
package io.magineer.nexthousear.helpers

import android.util.Log
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.concurrent.thread

data class CrimeInfo(
    val count: Int?,
    val errorMessage: String? = null
)

class CrimeDataHelper(private val httpClient: OkHttpClient) {

    /**
     * Fetches street-level crime reports near the selected point using Police.uk public data.
     */
    fun fetchCrimeInfo(
        latitude: Double,
        longitude: Double,
        callback: (CrimeInfo) -> Unit
    ) {
        thread {
            try {
                val url = "https://data.police.uk/api/crimes-street/all-crime?lat=$latitude&lng=$longitude"
                Log.d(TAG, "Police API crime request url=$url")

                val request = Request.Builder().url(url).build()
                val response = httpClient.newCall(request).execute()
                val body = response.body?.string() ?: ""

                Log.d(TAG, "Police API crime response code=${response.code} bodyLength=${body.length}")

                if (response.isSuccessful && body.isNotEmpty()) {
                    val crimes = JsonParser.parseString(body).asJsonArray
                    Log.d(TAG, "Parsed Police API crime count=${crimes.size()}")
                    callback(CrimeInfo(crimes.size()))
                } else {
                    val message = "Police API error ${response.code}"
                    Log.e(TAG, "Police API crime failed message=$message body=$body")
                    callback(CrimeInfo(null, message))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch crime data from Police API", e)
                callback(CrimeInfo(null, e.message ?: "Police API failed"))
            }
        }
    }

    companion object {
        private const val TAG = "CrimeDataHelper"
    }
}
