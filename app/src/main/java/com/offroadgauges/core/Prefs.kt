package com.offroadgauges.core

import android.content.Context
import android.content.SharedPreferences

/** Persistent settings: inclinometer zeroing, phone orientation, last temperature. */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("gauges", Context.MODE_PRIVATE)

    var rollOffset: Float
        get() = sp.getFloat("roll_off", 0f)
        set(v) = sp.edit().putFloat("roll_off", v).apply()

    var pitchOffset: Float
        get() = sp.getFloat("pitch_off", 0f)
        set(v) = sp.edit().putFloat("pitch_off", v).apply()

    /** true if the phone is rotated by 180 degrees (top edge toward the driver). */
    var rotated180: Boolean
        get() = sp.getBoolean("rotated180", false)
        set(v) = sp.edit().putBoolean("rotated180", v).apply()

    fun saveTemp(tempC: Float, atMs: Long) {
        sp.edit().putFloat("temp_c", tempC).putLong("temp_at", atMs).apply()
    }

    fun cachedTemp(): Pair<Float, Long>? {
        if (!sp.contains("temp_c")) return null
        return Pair(sp.getFloat("temp_c", 0f), sp.getLong("temp_at", 0L))
    }

    // Gauges shown in partial display mode. Indices: 0 temperature, 1 compass, 2 clock,
    // 3 altimeter, 4 roll, 5 pitch.
    /** With 2 gauges (about 2/3 of the screen): first and second, always distinct. */
    var twoA: Int
        get() = sp.getInt("two_a", 4).coerceIn(0, 5)
        set(v) = sp.edit().putInt("two_a", v.coerceIn(0, 5)).apply()

    var twoB: Int
        get() = sp.getInt("two_b", 5).coerceIn(0, 5)
        set(v) = sp.edit().putInt("two_b", v.coerceIn(0, 5)).apply()

    /** With 1 gauge (about 1/3 of the screen). */
    var one: Int
        get() = sp.getInt("one", 4).coerceIn(0, 5)
        set(v) = sp.edit().putInt("one", v.coerceIn(0, 5)).apply()
}
