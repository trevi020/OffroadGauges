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
 * Schermata sul telefono: permessi, stato dei sensori, guida animata di posizionamento,
 * lettura live degli inclinometri e azzeramento di prova.
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
        root.addView(tv("Strumenti da fuoristrada digitali per Android Auto", 14f, muted, top = 2))

        status = tv("", 14f, ink, top = 16)
        root.addView(status)

        permBtn = Button(this).apply {
            text = "Concedi permessi (posizione e notifiche)"
            setOnClickListener { requestBasePermissions() }
        }
        root.addView(permBtn)

        bgBtn = Button(this).apply {
            text = "Posizione \"sempre\" (consigliato per Android Auto)"
            setOnClickListener { requestBackgroundLocation() }
        }
        root.addView(bgBtn)

        root.addView(tv("Come posizionare il telefono", 18f, gold, true, top = 22))
        val guide = PlacementGuideView(this)
        root.addView(guide, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(290)).apply { topMargin = dp(8) })

        root.addView(
            tv(
                "1. Appoggia il telefono completamente piatto, schermo verso l'alto, sul supporto o sul piano di ricarica.\n" +
                    "2. Il lato lungo deve essere parallelo all'asse dell'auto, con il bordo superiore (fotocamera/notch) verso il parabrezza.\n" +
                    "3. Parcheggia l'auto su un piano orizzontale e tienila ferma.\n" +
                    "4. Premi AZZERA 0° dallo schermo di Android Auto (schermata Assetto) oppure da qui sotto.\n" +
                    "5. Se il telefono e' girato di 180° (bordo superiore verso di te) attiva l'opzione qui sotto, poi azzera di nuovo.\n" +
                    "6. Se il supporto e' inclinato di serie va bene: l'azzeramento compensa fino a 25°.",
                14f, ink, top = 12
            )
        )

        val flip = CheckBox(this).apply {
            text = "Telefono girato di 180° (bordo superiore verso il conducente)"
            setTextColor(ink)
            isChecked = com.offroadgauges.core.Prefs(this@MainActivity).rotated180
            setOnCheckedChangeListener { _, checked ->
                SensorHub.phoneRotated180 = checked
                Toast.makeText(this@MainActivity, "Orientamento cambiato: azzera di nuovo", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(flip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

        // ---- scelta dei quadranti per la visualizzazione parziale ----------------------------------
        val prefs = com.offroadgauges.core.Prefs(this)
        val names = arrayOf("Temperatura", "Bussola", "Orologio", "Altimetro", "Rollio", "Beccheggio")

        root.addView(tv("Visualizzazione parziale", 18f, gold, true, top = 22))
        root.addView(
            tv(
                "Quando l'app e' affiancata ad altre app (per esempio la musica) mostra solo i quadranti scelti qui, " +
                    "senza pagine. Se scegli rollio o beccheggio compare anche il pulsante AZZERA 0°.",
                13f, muted, top = 4
            )
        )

        root.addView(tv("Con 2 quadranti (circa 2/3 dello schermo): scegline 2", 15f, ink, true, top = 12))
        val two = ArrayDeque<Int>()                       // il piu' vecchio per primo
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
                    cb.isChecked = true   // ne servono sempre 2: per cambiare seleziona un altro quadrante
                    Toast.makeText(this, "Servono 2 quadranti: seleziona un altro per sostituirlo", Toast.LENGTH_SHORT).show()
                }
                updating = false
                val sorted = two.sorted()
                prefs.twoA = sorted[0]
                prefs.twoB = sorted[1]
            }
            root.addView(cb)
        }

        root.addView(tv("Con 1 quadrante (circa 1/3 dello schermo): scegli quale", 15f, ink, true, top = 14))
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

        root.addView(tv("Lettura live", 18f, gold, true, top = 18))
        live = tv("", 22f, ink, true, top = 6).apply { typeface = Typeface.MONOSPACE }
        root.addView(live)

        val zero = Button(this).apply {
            text = "AZZERA 0° ORA"
            setOnClickListener {
                val msg = when (SensorHub.calibrate()) {
                    SensorHub.CalResult.OK -> "Inclinometri azzerati"
                    SensorHub.CalResult.MOVING -> "Ferma l'auto per azzerare"
                    SensorHub.CalResult.TOO_TILTED -> "Telefono non in piano (oltre 25°)"
                    SensorHub.CalResult.NO_SENSOR -> "Sensore di inclinazione non disponibile"
                }
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(zero)

        root.addView(
            tv(
                "Collega il telefono ad Android Auto, apri l'elenco app e scegli Offroad Gauges. " +
                    "Per il test fuori dall'auto usa il Desktop Head Unit.",
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

    // ---- permessi --------------------------------------------------------------------------

    private fun hasFineLocation() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestBasePermissions() {
        val list = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(list.toTypedArray(), 1)
    }

    private fun requestBackgroundLocation() {
        when {
            Build.VERSION.SDK_INT < 29 -> Toast.makeText(this, "Non necessario su questa versione di Android", Toast.LENGTH_SHORT).show()
            !hasFineLocation() -> requestBasePermissions()
            Build.VERSION.SDK_INT == 29 -> requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 2)
            else -> {
                Toast.makeText(this, "Scegli Autorizzazioni > Posizione > Consenti sempre", Toast.LENGTH_LONG).show()
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

    // ---- stato -----------------------------------------------------------------------------

    private fun refreshStatus() {
        val loc = hasFineLocation()
        val sb = StringBuilder()
        sb.append("Posizione GPS: ").append(if (loc) "permesso concesso" else "PERMESSO MANCANTE").append('\n')
        sb.append("Sensore inclinazione: ").append(SensorHub.tiltSensorName).append('\n')
        sb.append("Barometro: ").append(if (SensorHub.hasBarometer) "presente" else "assente (pressione non disponibile)")
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
        live.text = "Rollio      ${f(r)}\nBeccheggio  ${f(p)}\n" +
            "Quota   ${if (alt == null) "--" else "${alt.roundToInt()} m"}\n" +
            "Pressione ${if (pr == null) "--" else "${pr.roundToInt()} hPa"}"
    }
}
