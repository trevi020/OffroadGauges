package com.offroadgauges.core

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Outside temperature from Open-Meteo (free, no API key). Call from a background thread. */
object WeatherRepo {
    fun fetchTemperature(lat: Double, lon: Double): Float? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(
                String.format(
                    Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m",
                    lat, lon
                )
            )
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
            }
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            JSONObject(body).getJSONObject("current").getDouble("temperature_2m").toFloat()
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
