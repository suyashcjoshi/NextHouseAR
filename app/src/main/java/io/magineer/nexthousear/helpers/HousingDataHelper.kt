/* Copyright 2026 Suyash Joshi */
package io.magineer.nexthousear.helpers

import android.util.Log
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import kotlin.concurrent.thread

data class HousingInfo(
    val price: Int,
    val date: String,
    val street: String,
    val town: String
)

data class HousingLookupResult(
    val info: HousingInfo?,
    val errorMessage: String? = null
)

class HousingDataHelper(private val httpClient: OkHttpClient) {

    /**
     * Fetches the latest sale for a postcode using HM Land Registry Price Paid Data.
     */
    fun fetchLatestSoldProperty(postcode: String, callback: (HousingInfo?) -> Unit) {
        fetchLatestSoldPropertyResult(postcode) { callback(it.info) }
    }

    fun fetchLatestSoldPropertyResult(postcode: String, callback: (HousingLookupResult) -> Unit) {
        thread {
            callback(fetchLandRegistryLatestSale(postcode))
        }
    }

    private fun fetchLandRegistryLatestSale(postcode: String): HousingLookupResult {
        return try {
            val url = "https://landregistry.data.gov.uk/data/ppi/transaction-record.json" +
                "?propertyAddress.postcode=${URLEncoder.encode(postcode.trim().uppercase(), "UTF-8")}" +
                "&_sort=-transactionDate&_pageSize=1"
            Log.d(TAG, "Land Registry request postcode=$postcode url=$url")

            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""

            Log.d(TAG, "Land Registry response code=${response.code} body=${body.previewForLog()}")

            if (response.isSuccessful && body.isNotEmpty()) {
                parseLandRegistrySale(JsonParser.parseString(body))?.let {
                    Log.d(TAG, "Parsed Land Registry sale price=${it.price} date=${it.date}")
                    return HousingLookupResult(it)
                }
                Log.w(TAG, "Land Registry returned no sale for postcode=$postcode")
                HousingLookupResult(null, "No Land Registry sale found")
            } else {
                val message = "Land Registry error ${response.code}"
                Log.e(TAG, "Land Registry failed message=$message body=$body")
                HousingLookupResult(null, message)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch Land Registry sale data", e)
            HousingLookupResult(null, e.message ?: "Land Registry failed")
        }
    }

    private fun parseLandRegistrySale(root: JsonElement): HousingInfo? {
        val items = root.asJsonObject
            .getAsJsonObject("result")
            ?.getAsJsonArray("items")
            ?: return null
        if (items.size() == 0 || !items[0].isJsonObject) return null

        val sale = items[0].asJsonObject
        val address = sale.getAsJsonObject("propertyAddress")
        val price = sale.intValue("pricePaid") ?: return null
        val date = sale.stringValue("transactionDate").orEmpty()
        val street = address?.stringValue("street") ?: ""
        val town = address?.stringValue("town") ?: ""

        return HousingInfo(price, date, street, town)
    }

    private fun JsonObject.stringValue(name: String): String? {
        val value = get(name) ?: return null
        return if (value.isJsonNull) null else value.asString
    }

    private fun JsonObject.intValue(name: String): Int? {
        val value = get(name) ?: return null
        return if (value.isJsonNull) null else value.asInt
    }

    private fun String.previewForLog(): String =
        if (length <= 500) this else "${take(500)}... (${length} chars)"

    companion object {
        private const val TAG = "HousingDataHelper"
    }
}
