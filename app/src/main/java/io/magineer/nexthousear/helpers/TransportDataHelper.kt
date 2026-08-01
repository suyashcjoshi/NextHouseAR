/* Copyright 2026 Suyash Joshi */
package io.magineer.nexthousear.helpers

import android.util.Log
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.concurrent.thread

data class StationInfo(
    val name: String,
    val distanceMeters: Int,
    val walkingMinutes: Int
)

class TransportDataHelper(private val httpClient: OkHttpClient) {

    /**
     * Fetches the nearest station (Tube/Rail) using TfL API.
     * London specific.
     */
    fun fetchNearestStation(lat: Double, lng: Double, callback: (StationInfo?) -> Unit) {
        thread {
            try {
                // radius in meters
                val url = "https://api.tfl.gov.uk/StopPoint/?lat=$lat&lon=$lng&stopTypes=NaptanMetroStation,NaptanRailStation&radius=1000"
                Log.d(TAG, "Fetching station data from: $url")
                
                val request = Request.Builder().url(url).build()
                val response = httpClient.newCall(request).execute()
                val body = response.body?.string() ?: ""
                
                if (response.isSuccessful && body.isNotEmpty()) {
                    val root = JsonParser.parseString(body).asJsonObject
                    val stopPoints = root.getAsJsonArray("stopPoints")
                    
                    if (stopPoints != null && stopPoints.size() > 0) {
                        // TfL returns sorted by distance usually, but let's find the minimum just in case
                        var nearest: StationInfo? = null
                        var minDistance = Int.MAX_VALUE
                        
                        for (element in stopPoints) {
                            val stop = element.asJsonObject
                            val distance = stop.get("distance").asDouble.toInt()
                            if (distance < minDistance) {
                                minDistance = distance
                                val name = stop.get("commonName").asString
                                // Walking speed approx 80m per minute
                                val minutes = (distance / 80) + 1 
                                nearest = StationInfo(name, distance, minutes)
                            }
                        }
                        callback(nearest)
                    } else {
                        callback(null)
                    }
                } else {
                    Log.e(TAG, "TfL API Error: ${response.code}")
                    callback(null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch transport data", e)
                callback(null)
            }
        }
    }

    companion object {
        private const val TAG = "TransportDataHelper"
    }
}
