package com.offroadgauges.core

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Unico punto di accesso a GPS, barometro e inclinazione. Usato sia dalla UI del telefono
 * sia dalla Session di Android Auto (conteggio dei riferimenti: parte al primo acquire(),
 * si ferma all'ultimo release()).
 *
 * Convenzione di montaggio del telefono: piatto, schermo verso l'alto, lato lungo parallelo
 * all'asse dell'auto, bordo superiore verso il parabrezza.
 *   asse X telefono = destra dell'auto, asse Y = avanti, asse Z = alto.
 *   rollio   positivo = auto inclinata verso destra (lato destro in basso)
 *   beccheggio positivo = muso verso l'alto
 */
object SensorHub : SensorEventListener, LocationListener {

    enum class CalResult { OK, MOVING, TOO_TILTED, NO_SENSOR }

    /** Offset geoide di riserva (Pianura Padana ~48 m) usato SOLO se ne' Android 14 ne' NMEA danno la quota sul livello del mare. */
    const val FALLBACK_GEOID_M = 48f

    private const val G = 9.80665f
    private const val HEADING_WINDOW_NS = 3_000_000_000L
    private const val MIN_HEADING_SPEED = 1.5f           // m/s (~5,4 km/h)
    private const val PRESSURE_WINDOW_MS = 60_000L
    private const val MAX_CAL_TILT_DEG = 25f
    private const val FIX_FRESH_MS = 3500L

    private var refs = 0
    private var app: Context? = null
    private var prefs: Prefs? = null
    private var sm: SensorManager? = null
    private var lm: LocationManager? = null
    private val ui = Handler(Looper.getMainLooper())
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ---- inclinazione -------------------------------------------------------------------
    @Volatile private var gx = 0f
    @Volatile private var gy = 0f
    @Volatile private var gz = 0f
    @Volatile private var tiltReady = false
    private var tiltInit = false
    @Volatile private var usingGravity = false
    @Volatile private var rollOff = 0f
    @Volatile private var pitchOff = 0f
    @Volatile private var rotated = false

    @Volatile var hasBarometer = false
        private set
    @Volatile var tiltSensorName = "assente"
        private set

    // ---- pressione (media mobile 60 s) --------------------------------------------------
    private val pLock = Any()
    private val pBuf = ArrayDeque<Pair<Long, Float>>()
    private var pSum = 0.0
    @Volatile var pressureAvg: Float? = null
        private set

    // ---- posizione ----------------------------------------------------------------------
    private class HeadingSample(val tNs: Long, val speed: Float, val bearing: Float)

    private val hBuf = ArrayDeque<HeadingSample>()
    @Volatile var heading: Float? = null
        private set
    @Volatile var headingLive = false
        private set
    @Volatile var altitude: Float? = null
        private set
    @Volatile var speedMps = 0f
        private set
    @Volatile private var lastFixMs = 0L
    @Volatile private var nmeaMsl: Float? = null
    @Volatile private var nmeaAtMs = 0L
    private var altInit = false

    val hasFix: Boolean
        get() = SystemClock.elapsedRealtime() - lastFixMs < FIX_FRESH_MS && lastFixMs != 0L

    // ---- meteo --------------------------------------------------------------------------
    @Volatile var tempC: Float? = null
        private set
    @Volatile private var tempAtMs = 0L
    private var weatherBusy = false
    private var lastWeatherTry = 0L
    private var haveWeatherLoc = false
    private var wLat = 0.0
    private var wLon = 0.0

    /** Eta' del dato di temperatura in ms (Long.MAX_VALUE se assente). */
    val tempAgeMs: Long
        get() = if (tempC == null || tempAtMs == 0L) Long.MAX_VALUE else System.currentTimeMillis() - tempAtMs

    // ======================================================================================
    // Ciclo di vita
    // ======================================================================================

    @Synchronized
    fun acquire(context: Context) {
        refs++
        if (refs == 1) start(context.applicationContext)
    }

    @Synchronized
    fun release() {
        if (refs == 0) return
        refs--
        if (refs == 0) stop()
    }

    private fun start(ctx: Context) {
        app = ctx
        val pr = Prefs(ctx)
        prefs = pr
        rollOff = pr.rollOffset
        pitchOff = pr.pitchOffset
        rotated = pr.rotated180
        pr.cachedTemp()?.let { (t, at) ->
            if (System.currentTimeMillis() - at < 6 * 3600_000L) {
                tempC = t
                tempAtMs = at
            }
        }

        val s = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sm = s
        val grav = s.getDefaultSensor(Sensor.TYPE_GRAVITY)
        val acc = s.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        usingGravity = grav != null
        val tilt = grav ?: acc
        tiltInit = false
        tiltReady = false
        if (tilt != null) {
            tiltSensorName = if (grav != null) "gravita' (fusione giroscopio)" else "accelerometro"
            s.registerListener(this, tilt, SensorManager.SENSOR_DELAY_GAME)
        } else {
            tiltSensorName = "assente"
        }
        val baro = s.getDefaultSensor(Sensor.TYPE_PRESSURE)
        hasBarometer = baro != null
        if (baro != null) s.registerListener(this, baro, SensorManager.SENSOR_DELAY_NORMAL)

        lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        startLocation()
    }

    private fun stop() {
        sm?.unregisterListener(this)
        stopLocation()
        synchronized(pLock) {
            pBuf.clear(); pSum = 0.0; pressureAvg = null
        }
        hBuf.clear()
        heading = null
        headingLive = false
        tiltReady = false
        altInit = false
        altitude = null
        lastFixMs = 0L
    }

    /** Da chiamare dopo che l'utente ha concesso il permesso di posizione. */
    fun restartLocation() {
        ui.post { startLocation() }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun startLocation() {
        val ctx = app ?: return
        val m = lm ?: return
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        try {
            m.removeUpdates(this)
            m.removeNmeaListener(nmea)
        } catch (_: Exception) {
        }
        try {
            m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
            m.addNmeaListener(nmea, ui)
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun stopLocation() {
        try {
            lm?.removeUpdates(this)
            lm?.removeNmeaListener(nmea)
        } catch (_: Exception) {
        }
    }

    // ======================================================================================
    // Inclinometri
    // ======================================================================================

    val tiltAvailable: Boolean get() = tiltReady

    private fun rawRoll(): Float {
        val x = if (rotated) -gx else gx
        return Math.toDegrees(atan2(-x, gz).toDouble()).toFloat()
    }

    private fun rawPitch(): Float {
        val x = if (rotated) -gx else gx
        val y = if (rotated) -gy else gy
        // Muso in alto => l'asse Y del telefono (verso il parabrezza) punta in alto => gy > 0
        return Math.toDegrees(atan2(y, hypot(x, gz)).toDouble()).toFloat()
    }

    /** Rollio in gradi, gia' azzerato. null se il sensore non e' disponibile. */
    fun roll(): Float? = if (!tiltReady) null else (rawRoll() - rollOff).coerceIn(-90f, 90f)

    /** Beccheggio in gradi, gia' azzerato. null se il sensore non e' disponibile. */
    fun pitch(): Float? = if (!tiltReady) null else (rawPitch() - pitchOff).coerceIn(-90f, 90f)

    var phoneRotated180: Boolean
        get() = rotated
        set(v) {
            rotated = v
            rollOff = 0f
            pitchOff = 0f
            app?.let { Prefs(it).apply { rotated180 = v; rollOffset = 0f; pitchOffset = 0f } }
        }

    /** Azzera gli inclinometri: telefono fermo, auto in piano. */
    fun calibrate(): CalResult {
        if (!tiltReady) return CalResult.NO_SENSOR
        if (hasFix && speedMps > 1f) return CalResult.MOVING
        val r = rawRoll()
        val p = rawPitch()
        if (hypot(r, p) > MAX_CAL_TILT_DEG) return CalResult.TOO_TILTED
        rollOff = r
        pitchOff = p
        prefs?.let { it.rollOffset = r; it.pitchOffset = p }
        return CalResult.OK
    }

    // ======================================================================================
    // SensorEventListener
    // ======================================================================================

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_GRAVITY, Sensor.TYPE_ACCELEROMETER -> onTilt(e.values[0], e.values[1], e.values[2])
            Sensor.TYPE_PRESSURE -> onPressure(e.values[0])
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun onTilt(ax: Float, ay: Float, az: Float) {
        if (!tiltInit) {
            gx = ax; gy = ay; gz = az
            tiltInit = true
            tiltReady = true
            return
        }
        val alpha = if (usingGravity) {
            0.12f
        } else {
            // Accelerometro grezzo: filtro passa-basso piu' pesante e pesato sulla vicinanza a 1 g,
            // cosi' frenate e curve (|a| lontano da g) non sporcano l'angolo.
            val mag = sqrt(ax * ax + ay * ay + az * az)
            0.03f * (1f - min(abs(mag - G) / 1.5f, 1f))
        }
        gx += alpha * (ax - gx)
        gy += alpha * (ay - gy)
        gz += alpha * (az - gz)
    }

    private fun onPressure(hPa: Float) {
        val now = SystemClock.elapsedRealtime()
        synchronized(pLock) {
            pBuf.addLast(Pair(now, hPa))
            pSum += hPa
            while (pBuf.isNotEmpty() && now - pBuf.first().first > PRESSURE_WINDOW_MS) {
                pSum -= pBuf.removeFirst().second
            }
            pressureAvg = if (pBuf.isEmpty()) null else (pSum / pBuf.size).toFloat()
        }
    }

    // ======================================================================================
    // LocationListener
    // ======================================================================================

    override fun onLocationChanged(loc: Location) {
        lastFixMs = SystemClock.elapsedRealtime()
        val speed = if (loc.hasSpeed()) loc.speed else 0f
        speedMps = speed

        // Quota sul livello del mare (MSL), in ordine di preferenza:
        // 1) Android 14+ (geoide EGM96 del sistema); 2) campo MSL della frase NMEA GGA; 3) ellissoide - offset fisso.
        val msl: Float? = when {
            Build.VERSION.SDK_INT >= 34 && loc.hasMslAltitude() -> loc.mslAltitudeMeters.toFloat()
            nmeaMsl != null && SystemClock.elapsedRealtime() - nmeaAtMs < 5000L -> nmeaMsl
            loc.hasAltitude() -> (loc.altitude - FALLBACK_GEOID_M).toFloat()
            else -> null
        }
        if (msl != null) {
            val prev = altitude
            altitude = if (!altInit || prev == null) {
                altInit = true
                msl
            } else {
                prev + 0.25f * (msl - prev)
            }
        }

        // Direzione di marcia: media vettoriale (pesata sulla velocita') dei bearing GPS degli ultimi 3 s.
        val nowNs = loc.elapsedRealtimeNanos
        if (loc.hasBearing() && speed >= MIN_HEADING_SPEED) {
            hBuf.addLast(HeadingSample(nowNs, speed, loc.bearing))
        }
        while (hBuf.isNotEmpty() && nowNs - hBuf.first().tNs > HEADING_WINDOW_NS) hBuf.removeFirst()
        if (hBuf.isNotEmpty()) {
            var sx = 0.0
            var sy = 0.0
            for (h in hBuf) {
                val r = Math.toRadians(h.bearing.toDouble())
                sx += h.speed * sin(r)
                sy += h.speed * cos(r)
            }
            if (hypot(sx, sy) > 1e-3) {
                heading = ((Math.toDegrees(atan2(sx, sy)) + 360.0) % 360.0).toFloat()
            }
            headingLive = true
        } else {
            headingLive = false   // fermo: si tiene l'ultima direzione nota
        }

        maybeRefreshWeather(loc.latitude, loc.longitude)
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
    }

    // ======================================================================================
    // NMEA: quota MSL dal campo 9 della frase GGA
    // ======================================================================================

    private val nmea = OnNmeaMessageListener { message, _ -> parseGga(message) }

    private fun parseGga(raw: String) {
        val s = raw.trim()
        if (s.length < 7 || s[0] != '$' || !s.regionMatches(3, "GGA", 0, 3)) return
        val f = s.split(',')
        if (f.size < 10) return
        if ((f[6].toIntOrNull() ?: 0) == 0) return
        val alt = f[9].toFloatOrNull() ?: return
        nmeaMsl = alt
        nmeaAtMs = SystemClock.elapsedRealtime()
    }

    // ======================================================================================
    // Meteo
    // ======================================================================================

    private fun maybeRefreshWeather(lat: Double, lon: Double) {
        val now = System.currentTimeMillis()
        var moved = false
        if (haveWeatherLoc) {
            val d = FloatArray(1)
            Location.distanceBetween(wLat, wLon, lat, lon, d)
            moved = d[0] > 10_000f
        }
        val retryAfter = if (tempC == null) 60_000L else 600_000L
        val due = now - lastWeatherTry > retryAfter
        if (weatherBusy || !(due || moved)) return
        weatherBusy = true
        lastWeatherTry = now
        io.launch {
            val t = WeatherRepo.fetchTemperature(lat, lon)
            if (t != null) {
                tempC = t
                tempAtMs = System.currentTimeMillis()
                wLat = lat
                wLon = lon
                haveWeatherLoc = true
                prefs?.saveTemp(t, tempAtMs)
            }
            weatherBusy = false
        }
    }
}
