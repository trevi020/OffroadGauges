package com.offroadgauges.core

import android.content.Context
import android.content.SharedPreferences

/** Impostazioni persistenti: azzeramento inclinometri, orientamento telefono, ultima temperatura. */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("gauges", Context.MODE_PRIVATE)

    var rollOffset: Float
        get() = sp.getFloat("roll_off", 0f)
        set(v) = sp.edit().putFloat("roll_off", v).apply()

    var pitchOffset: Float
        get() = sp.getFloat("pitch_off", 0f)
        set(v) = sp.edit().putFloat("pitch_off", v).apply()

    /** true se il telefono e' girato di 180 gradi (bordo superiore verso il conducente). */
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

    // Quadranti mostrati con visualizzazione parziale. Indici: 0 temperatura, 1 bussola, 2 orologio,
    // 3 altimetro, 4 rollio, 5 beccheggio.
    /** Con 2 quadranti (circa 2/3 dello schermo): primo e secondo, sempre distinti. */
    var twoA: Int
        get() = sp.getInt("two_a", 4).coerceIn(0, 5)
        set(v) = sp.edit().putInt("two_a", v.coerceIn(0, 5)).apply()

    var twoB: Int
        get() = sp.getInt("two_b", 5).coerceIn(0, 5)
        set(v) = sp.edit().putInt("two_b", v.coerceIn(0, 5)).apply()

    /** Con 1 quadrante (circa 1/3 dello schermo). */
    var one: Int
        get() = sp.getInt("one", 4).coerceIn(0, 5)
        set(v) = sp.edit().putInt("one", v.coerceIn(0, 5)).apply()
}
