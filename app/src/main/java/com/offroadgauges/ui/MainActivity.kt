package com.offroadgauges.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.offroadgauges.core.SensorHub
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Phone screen: permissions, sensor status, animated placement guide,
 * live inclinometer readout and a test zero-calibration.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var live: TextView
    private lateinit var permBtn: Button
    private lateinit var bgBtn: Button
    private val ui = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refreshLive()
            ui.postDelayed(this, 250L)
        }
    }

    private val ink = 0xFFF2ECE0.toInt()
    private val muted = 0xFF9AA3B5.toInt()
    private val gold = 0xFFD8B574.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(32))
        }

        fun tv(text: String, sp: Float, color: Int = ink, bold: Boolean = false, top: Int = 0): TextView =
            TextView(this).apply {
                this.text = text
                textSize = sp
                setTextColor(color)
                if (bold) setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(top), 0, 0)
            }

        root.addView(tv("Offroad Gauges", 26f, gold, true))
        root.addView(tv("Digital off-road gauges for Android Auto", 14f, muted, top = 2))

        status = tv("", 14f, ink, top = 16)
        root.addView(status)

        permBtn = Button(this).apply {
            text = "Grant permissions (location and notifications)"
            setOnClickListener { requestBasePermissions() }
        }
        root.addView(permBtn)

        bgBtn = Button(this).apply {
            text = "Location \"always\" (recommended for Android Auto)"
            setOnClickListener { requestBackgroundLocation() }
        }
        root.addView(bgBtn)

        root.addView(tv("How to place the phone", 18f, gold, true, top = 22))
        val guide = PlacementGuideView(this)
        root.addView(guide, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(290)).apply { topMargin = dp(8) })

        root.addView(
            tv(
                "1. Lay the phone completely flat, screen up, on its mount or charging pad.\n" +
                    "2. The long side must be parallel to the car's axis, with the top edge (camera/notch) toward the windshield.\n" +
                    "3. Park the car on level ground and keep it still.\n" +
                    "4. Press ZERO 0° on the Android Auto screen (Attitude page) or from here below.\n" +
                    "5. If the phone is rotated 180° (top edge toward you) enable the option below, then zero again.\n" +
                    "6. A mount that is tilted by design is fine: zeroing compensates up to 25°.",
                14f, ink, top = 12
            )
        )

        val flip = CheckBox(this).apply {
            text = "Phone rotated 180° (top edge toward the driver)"
            setTextColor(ink)
            isChecked = com.offroadgauges.core.Prefs(this@MainActivity).rotated180
            setOnCheckedChangeListener { _, checked ->
                SensorHub.phoneRotated180 = checked
                Toast.makeText(this@MainActivity, "Orientation changed: zero again", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(flip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

        // ---- choice of gauges for the partial display modes ----------------------------------------
        val prefs = com.offroadgauges.core.Prefs(this)
        val names = arrayOf("Temperature", "Compass", "Clock", "Altimeter", "Roll", "Pitch")

        root.addView(tv("Partial view", 18f, gold, true, top = 22))
        root.addView(
            tv(
                "When the app is shown next to other apps (for example music) it displays only the gauges chosen here, " +
                    "without pages. If you choose roll or pitch the ZERO 0° button also appears.",
                13f, muted, top = 4
            )
        )

        root.addView(tv("With 2 gauges (about 2/3 of the screen): pick 2", 15f, ink, true, top = 12))
        val two = ArrayDeque<Int>()                       // the oldest first
        val firstTwo = prefs.twoA
        var secondTwo = prefs.twoB
        if (secondTwo == firstTwo) secondTwo = (firstTwo + 1) % 6
        two.addLast(firstTwo)
        two.addLast(secondTwo)
        val twoBoxes = ArrayList<CheckBox>()
        var updating = false
        for (i in names.indices) {
            val cb = CheckBox(this)
            cb.text = names[i]
            cb.setTextColor(ink)
            cb.isChecked = two.contains(i)
            twoBoxes.add(cb)
            cb.setOnCheckedChangeListener { _, checked ->
                if (updating) return@setOnCheckedChangeListener
                updating = true
                if (checked) {
                    two.addLast(i)
                    while (two.size > 2) {
                        val old = two.removeFirst()
                        twoBoxes[old].isChecked = false
                    }
                } else {
                    cb.isChecked = true   // always 2 are needed: to change, select another gauge
                    Toast.makeText(this, "Two gauges are required: select another one to replace it", Toast.LENGTH_SHORT).show()
                }
                updating = false
                val sorted = two.sorted()
                prefs.twoA = sorted[0]
                prefs.twoB = sorted[1]
            }
            root.addView(cb)
        }

        root.addView(tv("With 1 gauge (about 1/3 of the screen): pick which", 15f, ink, true, top = 14))
        val rg = android.widget.RadioGroup(this)
        for (i in names.indices) {
            val rb = android.widget.RadioButton(this)
            rb.id = 100 + i
            rb.text = names[i]
            rb.setTextColor(ink)
            rg.addView(rb)
        }
        rg.check(100 + prefs.one)
        rg.setOnCheckedChangeListener { _, checkedId -> prefs.one = checkedId - 100 }
        root.addView(rg)

        root.addView(tv("Live readout", 18f, gold, true, top = 18))
        live = tv("", 22f, ink, true, top = 6).apply { typeface = Typeface.MONOSPACE }
        root.addView(live)

        val zero = Button(this).apply {
            text = "ZERO 0° NOW"
            setOnClickListener {
                val msg = when (SensorHub.calibrate()) {
                    SensorHub.CalResult.OK -> "Inclinometers zeroed"
                    SensorHub.CalResult.MOVING -> "Stop the car to zero"
                    SensorHub.CalResult.TOO_TILTED -> "Phone not level (over 25°)"
                    SensorHub.CalResult.NO_SENSOR -> "Tilt sensor not available"
                }
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(zero)

        root.addView(
            tv(
                "Connect the phone to Android Auto, open the app list and choose Offroad Gauges. " +
                    "To test outside the car use the Desktop Head Unit.",
                13f, muted, top = 18
            )
        )

        setContentView(ScrollView(this).apply {
            setBackgroundColor(0xFF0B1020.toInt())
            isFillViewport = true
            addView(root)
        })
    }

    override fun onResume() {
        super.onResume()
        SensorHub.acquire(this)
        refreshStatus()
        ui.post(ticker)
    }

    override fun onPause() {
        ui.removeCallbacks(ticker)
        SensorHub.release()
        super.onPause()
    }

    // ---- permissions -----------------------------------------------------------------------

    private fun hasFineLocation() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestBasePermissions() {
        val list = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(list.toTypedArray(), 1)
    }

    private fun requestBackgroundLocation() {
        when {
            Build.VERSION.SDK_INT < 29 -> Toast.makeText(this, "Not needed on this Android version", Toast.LENGTH_SHORT).show()
            !hasFineLocation() -> requestBasePermissions()
            Build.VERSION.SDK_INT == 29 -> requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 2)
            else -> {
                Toast.makeText(this, "Choose Permissions > Location > Allow all the time", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        SensorHub.restartLocation()
        refreshStatus()
    }

    // ---- status ----------------------------------------------------------------------------

    private fun refreshStatus() {
        val loc = hasFineLocation()
        val sb = StringBuilder()
        sb.append("GPS location: ").append(if (loc) "permission granted" else "PERMISSION MISSING").append('\n')
        sb.append("Tilt sensor: ").append(SensorHub.tiltSensorName).append('\n')
        sb.append("Barometro: ").append(if (SensorHub.hasBarometer) "present" else "absent (pressure not available)")
        status.text = sb.toString()
        permBtn.visibility = if (loc) View.GONE else View.VISIBLE
        bgBtn.visibility = if (Build.VERSION.SDK_INT >= 29 && loc) View.VISIBLE else View.GONE
    }

    private fun refreshLive() {
        val r = SensorHub.roll()
        val p = SensorHub.pitch()
        fun f(v: Float?) = if (v == null) "  --" else String.format(Locale.US, "%+5.1f°", v)
        val pr = SensorHub.pressureAvg
        val alt = SensorHub.altitude
        live.text = "Roll      ${f(r)}\nPitch     ${f(p)}\n" +
            "Altitude ${if (alt == null) "--" else "${alt.roundToInt()} m"}\n" +
            "Pressure ${if (pr == null) "--" else "${pr.roundToInt()} hPa"}"
    }
}
