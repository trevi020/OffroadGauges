package com.offroadgauges.core

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Temperatura esterna da Open-Meteo (gratuito, senza chiave API). Chiamare da thread di background. */
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
