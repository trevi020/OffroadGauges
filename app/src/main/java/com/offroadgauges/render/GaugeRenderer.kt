package com.offroadgauges.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import com.offroadgauges.core.Damper
import com.offroadgauges.core.Prefs
import com.offroadgauges.core.SensorHub
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Draws the two gauge screens on an Android Auto Surface.
 * The whole layout is defined in a 1280x720 design space and scaled (letterboxed)
 * into the stable area provided by the host, so it works on any head unit resolution.
 */
class GaugeRenderer(private val prefs: Prefs) {

    // Pages side by side horizontally: 0 = Ambient, 1 = Attitude.
    // pagePos goes from 0 to 1 and is the current scroll position (dragged by finger or animated).
    @Volatile private var targetPage = 0
    @Volatile private var pagePos = 0f
    @Volatile private var dragging = false
    @Volatile private var lastScrollMs = 0L
    private var gaugeScale = 1f

    // Display mode, decided by the width of the visible area relative to the Surface:
    // full (3 gauges, 2 pages with swipe), 2/3 (2 chosen gauges), 1/3 (1 chosen gauge).
    private enum class Mode { FULL, TWO, ONE }

    @Volatile private var mode = Mode.FULL
    @Volatile private var visible: Rect? = null
    @Volatile private var designW = 1280f
    @Volatile private var zeroVisible = false

    private val thread = HandlerThread("gauge-render").also { it.start() }
    private val handler = Handler(thread.looper)
    private var surface: Surface? = null
    private var sw = 1
    private var sh = 1
    @Volatile
    private var stable: Rect? = null
    private var running = false
    private var lastNs = 0L

    // "glycerin" dampers
    private val headDamper = Damper()
    private val rollDamper = Damper()
    private val pitchDamper = Damper()
    private val altDamper = Damper()
    private var headInit = false
    private var altInit = false

    // temporary message
    @Volatile private var flashText: String? = null
    @Volatile private var flashOk = true
    @Volatile private var flashUntil = 0L

    // touchable areas (design space)
    private val tabAmb = RectF()
    private val tabAss = RectF()
    private val zeroBtn = RectF()
    private var scale = 1f
    private var offX = 0f
    private var offY = 0f

    // clock
    private val cal = Calendar.getInstance()
    private val dateFmt = SimpleDateFormat("dd/MM EEE", Locale.ENGLISH)
    private var lastDay = -1
    private var dateStr = ""

    // ---- drawing tools ----------------------------------------------------------------
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private val sans: Typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    private val sansBold: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)

    private var bgShader: LinearGradient? = null
    private var bgH = 0
    private val stars = FloatArray(60).also {
        val r = java.util.Random(7)
        for (i in it.indices) it[i] = r.nextFloat()
    }
    private val mtX = floatArrayOf(0f, 120f, 220f, 340f, 470f, 560f, 700f, 820f, 960f, 1100f, 1280f)
    private val mtY = floatArrayOf(230f, 170f, 200f, 120f, 190f, 150f, 210f, 140f, 200f, 130f, 190f)

    private val waveFill = Path().apply {
        moveTo(-170f, 26f)
        cubicTo(-130f, 26f, -110f, -20f, -80f, -20f)
        cubicTo(-55f, -20f, -45f, 8f, -20f, 12f)
        cubicTo(20f, 18f, 60f, 6f, 170f, 6f)
        lineTo(170f, 170f)
        lineTo(-170f, 170f)
        close()
    }
    private val waveLine = Path().apply {
        moveTo(-170f, 26f)
        cubicTo(-130f, 26f, -110f, -20f, -80f, -20f)
        cubicTo(-55f, -20f, -45f, 8f, -20f, 12f)
        cubicTo(20f, 18f, 60f, 6f, 170f, 6f)
    }
    private val clip = Path().apply { addCircle(0f, 0f, 162f, Path.Direction.CW) }

    // ======================================================================================
    // Public API
    // ======================================================================================

    fun attach(s: Surface, w: Int, h: Int) {
        handler.post {
            surface = s
            sw = max(1, w)
            sh = max(1, h)
            if (!running) {
                running = true
                lastNs = System.nanoTime()
                handler.post(frame)
            }
        }
    }

    /** Blocks until the drawing loop has released the Surface. */
    fun detach() {
        val latch = CountDownLatch(1)
        handler.post {
            running = false
            handler.removeCallbacks(frame)
            surface = null
            latch.countDown()
        }
        latch.await(500, TimeUnit.MILLISECONDS)
    }

    fun release() {
        detach()
        thread.quitSafely()
    }

    fun setStableArea(r: Rect) {
        stable = Rect(r)
    }

    fun setVisibleArea(r: Rect) {
        visible = Rect(r)
    }

    fun goTo(page: Int) {
        dragging = false
        targetPage = page.coerceIn(0, 1)
    }

    fun togglePage() {
        goTo(1 - targetPage)
    }

    /** Finger drag (GestureDetector convention: finger to the left = positive distanceX). */
    fun onScroll(distanceX: Float) {
        if (mode != Mode.FULL) return
        val w = max(1f, scale * designW)
        dragging = true
        lastScrollMs = SystemClock.uptimeMillis()
        pagePos = (pagePos + distanceX / w).coerceIn(0f, 1f)
    }

    /** Fling release: negative velocityX = finger to the left = next page. */
    fun onFling(velocityX: Float) {
        dragging = false
        targetPage = when {
            velocityX < -300f -> 1
            velocityX > 300f -> 0
            else -> if (pagePos > 0.5f) 1 else 0
        }
    }

    fun requestCalibration() {
        val res = SensorHub.calibrate()
        when (res) {
            SensorHub.CalResult.OK -> flash("INCLINOMETERS ZEROED", true)
            SensorHub.CalResult.MOVING -> flash("STOP THE CAR TO ZERO", false)
            SensorHub.CalResult.TOO_TILTED -> flash("PHONE NOT LEVEL (OVER 25°)", false)
            SensorHub.CalResult.NO_SENSOR -> flash("TILT SENSOR NOT AVAILABLE", false)
        }
    }

    fun onClick(x: Float, y: Float) {
        val dx = (x - offX) / scale
        val dy = (y - offY) / scale
        if (mode == Mode.FULL) {
            val shift = (1f - pagePos) * designW
            when {
                hitY(tabAmb, dx, dy) -> goTo(0)
                hitY(tabAss, dx, dy) -> goTo(1)
                pagePos > 0.5f && hitY(zeroBtn, dx - shift, dy) -> requestCalibration()
            }
        } else if (zeroVisible && hitY(zeroBtn, dx, dy)) {
            requestCalibration()
        }
    }

    /** Touchable area: the rectangle extended by 14 units vertically (easier target in the car). */
    private fun hitY(r: RectF, x: Float, y: Float): Boolean =
        x >= r.left && x <= r.right && y >= r.top - 14f && y <= r.bottom + 14f

    private fun flash(text: String, ok: Boolean) {
        flashText = text
        flashOk = ok
        flashUntil = SystemClock.uptimeMillis() + 2500L
    }

    // ======================================================================================
    // Loop
    // ======================================================================================

    private val frame = object : Runnable {
        override fun run() {
            if (!running) return
            val start = SystemClock.uptimeMillis()
            drawOnce()
            val spent = SystemClock.uptimeMillis() - start
            handler.postDelayed(this, max(1L, 33L - spent))
        }
    }

    private fun drawOnce() {
        val s = surface ?: return
        if (!s.isValid) return
        val c: Canvas = try {
            s.lockHardwareCanvas()
        } catch (e: Exception) {
            try {
                s.lockCanvas(null)
            } catch (e2: Exception) {
                return
            }
        }
        try {
            render(c)
        } finally {
            try {
                s.unlockCanvasAndPost(c)
            } catch (_: Exception) {
            }
        }
    }

    private fun render(c: Canvas) {
        val now = System.nanoTime()
        val dt = ((now - lastNs) / 1e9f).coerceIn(0.001f, 0.1f)
        lastNs = now
        val hub = SensorHub

        hub.heading?.let { h ->
            if (!headInit) {
                headDamper.value = h
                headInit = true
            }
            headDamper.updateAngle(h, dt, 0.7f)
        }
        hub.roll()?.let { rollDamper.update(it, dt, 0.5f) }
        hub.pitch()?.let { pitchDamper.update(it, dt, 0.5f) }
        hub.altitude?.let { a ->
            if (!altInit) {
                altDamper.value = a
                altInit = true
            }
            altDamper.update(a, dt, 1.2f)
        }

        drawBackground(c)

        // scrolling between pages: drag released -> nearest page, then animation
        if (dragging && SystemClock.uptimeMillis() - lastScrollMs > 160L) {
            dragging = false
            targetPage = if (pagePos > 0.5f) 1 else 0
        }
        if (!dragging) {
            val k = 1f - exp(-dt * 14f)
            pagePos += (targetPage - pagePos) * k
            if (abs(targetPage - pagePos) < 0.0005f) pagePos = targetPage.toFloat()
        }

        // Usable area: horizontally the visible area (excludes side-by-side cards, e.g. music),
        // vertically the whole Surface.
        val vis = visible
        var left = 0
        var right = sw
        if (vis != null && vis.width() > 0) {
            left = vis.left.coerceIn(0, sw - 1)
            right = vis.right.coerceIn(left + 1, sw)
        }
        val aw = right - left
        val frac = aw / sw.toFloat()
        val m = when {
            frac >= 0.8f -> Mode.FULL
            frac >= 0.45f -> Mode.TWO
            else -> Mode.ONE
        }
        mode = m

        // Design space: fixed height 520, adapted width. Texts and buttons have the same
        // apparent size in all modes.
        val hh = 520f
        val sc = sh / hh
        val ww = aw / sc
        scale = sc
        offX = left.toFloat()
        offY = 0f
        designW = ww

        // gauges chosen by the user for the partial modes (ordered, always distinct)
        val sel: IntArray = when (m) {
            Mode.FULL -> IntArray(0)
            Mode.TWO -> {
                val a = prefs.twoA
                var b = prefs.twoB
                if (b == a) b = (a + 1) % 6
                intArrayOf(min(a, b), max(a, b))
            }
            Mode.ONE -> intArrayOf(prefs.one)
        }
        val zeroNeeded = m != Mode.FULL && sel.any { it >= 4 }
        zeroVisible = zeroNeeded
        layoutBar(ww, hh, m)

        // Gauges as large as possible: limited by width (n circles) or height (minus titles and bar)
        val margin = 14f
        val gap = 14f
        val topPad = 8f
        val titleH = 34f
        val barH = if (m == Mode.FULL || zeroNeeded) tabBottom + tabH + 10f else 0f
        val availH = hh - topPad - barH - titleH
        val n = if (m == Mode.FULL) 3 else sel.size
        val dMax = (ww - 2f * margin - (n - 1) * gap) / n
        val dd = max(120f, min(dMax, availH))
        gaugeScale = dd / 340f
        val cy = topPad + availH / 2f
        val step = dd + gap

        c.save()
        c.translate(offX, offY)
        c.scale(sc, sc)
        c.clipRect(0f, 0f, ww, hh)

        if (m == Mode.FULL) {
            val pos = pagePos
            val x0 = ww / 2f - step
            val x1 = ww / 2f
            val x2 = ww / 2f + step
            if (pos < 0.999f) {
                c.save()
                c.translate(-pos * ww, 0f)
                drawGaugeById(c, 0, x0, cy)
                drawGaugeById(c, 1, x1, cy)
                drawGaugeById(c, 2, x2, cy)
                c.restore()
            }
            if (pos > 0.001f) {
                c.save()
                c.translate((1f - pos) * ww, 0f)
                drawGaugeById(c, 3, x0, cy)
                drawGaugeById(c, 4, x1, cy)
                drawGaugeById(c, 5, x2, cy)
                drawZeroButton(c)
                c.restore()
            }
            drawTabs(c, pos)
        } else {
            for (i in sel.indices) {
                drawGaugeById(c, sel[i], ww / 2f + (i - (n - 1) / 2f) * step, cy)
            }
            if (zeroNeeded) drawZeroButton(c)
        }
        drawFlash(c, ww, hh)
        c.restore()
    }

    /** 0 temperature, 1 compass, 2 clock, 3 altimeter, 4 roll, 5 pitch. */
    private fun drawGaugeById(c: Canvas, id: Int, cx: Float, cy: Float) {
        when (id) {
            0 -> gauge(c, cx, cy, "TEMPERATURE") { drawTemp(it) }
            1 -> gauge(c, cx, cy, "COMPASS") { drawCompass(it) }
            2 -> gauge(c, cx, cy, "CLOCK") { drawClock(it) }
            3 -> gauge(c, cx, cy, "ALTIMETER") { drawAltimeter(it) }
            4 -> gauge(c, cx, cy, "ROLL") { drawTilt(it, true) }
            else -> gauge(c, cx, cy, "PITCH") { drawTilt(it, false) }
        }
    }

    // ======================================================================================
    // Drawing helpers
    // ======================================================================================

    private val GOLD = 0xFFD8B574.toInt()
    private val TEXT = 0xFFF2ECE0.toInt()
    private val MUTED = 0xFF9AA3B5.toInt()
    private val DIM = 0xFF6B7488.toInt()
    private val TICK = 0xFF8D95A8.toInt()
    private val FACE = 0xD1050A14.toInt()
    private val TRACK = 0xFF2A3140.toInt()
    private val BROWN = 0xFF3A2F27.toInt()
    private val RED = 0xFFE5473D.toInt()
    private val GREEN = 0xFF4CD08A.toInt()
    private val CAR = 0xFFE9E6DF.toInt()
    private val CAR2 = 0xFFCFCAC0.toInt()
    private val GLASS = 0xFF2A3344.toInt()

    private fun fill(color: Int): Paint {
        p.reset(); p.isAntiAlias = true
        p.style = Paint.Style.FILL
        p.color = color
        return p
    }

    private fun stroke(color: Int, w: Float, cap: Paint.Cap = Paint.Cap.BUTT): Paint {
        p.reset(); p.isAntiAlias = true
        p.style = Paint.Style.STROKE
        p.color = color
        p.strokeWidth = w
        p.strokeCap = cap
        return p
    }

    private fun text(
        c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int,
        bold: Boolean = false, spacing: Float = 0f, align: Paint.Align = Paint.Align.CENTER
    ) {
        p.reset(); p.isAntiAlias = true
        p.style = Paint.Style.FILL
        p.color = color
        p.textSize = size
        p.typeface = if (bold) sansBold else sans
        p.letterSpacing = spacing / size
        p.textAlign = align
        c.drawText(s, x, y, p)
    }

    private fun measure(s: String, size: Float, bold: Boolean = false, spacing: Float = 0f): Float {
        p.reset(); p.isAntiAlias = true
        p.textSize = size
        p.typeface = if (bold) sansBold else sans
        p.letterSpacing = spacing / size
        return p.measureText(s)
    }

    private fun withAlpha(color: Int, f: Float): Int {
        val a = ((color ushr 24) * f.coerceIn(0f, 1f)).roundToInt()
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    private fun arcRect(r: Float): RectF {
        rect.set(-r, -r, r, r)
        return rect
    }

    /** Radial line at angle [deg] (0 = 3 o'clock, clockwise) between radii r0 and r1. */
    private fun radial(c: Canvas, deg: Float, r0: Float, r1: Float, paint: Paint) {
        val a = Math.toRadians(deg.toDouble())
        val ca = cos(a).toFloat()
        val sa = sin(a).toFloat()
        c.drawLine(ca * r0, sa * r0, ca * r1, sa * r1, paint)
    }

    private fun ticks(
        c: Canvas, rCenter: Float, len: Float, count: Int, w: Float, color: Int,
        startDeg: Float = -90f, stepDeg: Float = 360f / count
    ) {
        val paint = stroke(color, w)
        for (i in 0 until count) {
            radial(c, startDeg + i * stepDeg, rCenter - len / 2f, rCenter + len / 2f, paint)
        }
    }

    private inline fun gauge(c: Canvas, cx: Float, cy: Float, title: String, body: (Canvas) -> Unit) {
        val gs = gaugeScale
        c.save()
        c.translate(cx, cy)
        c.scale(gs, gs)
        c.drawCircle(0f, 0f, 164f, fill(FACE))
        c.drawCircle(0f, 0f, 164f, stroke(GOLD, 2f))
        body(c)
        c.restore()
        text(c, title, cx, cy + 170f * gs + 24f, 18f, MUTED, spacing = 4f)
    }

    // ======================================================================================
    // Background and status
    // ======================================================================================

    private var bgBitmap: Bitmap? = null
    private var bgW = 0
    private var bgH2 = 0

    /**
     * Gradient background (black -> midnight blue -> black) built only once, with dithering.
     * The H.264 video of Android Auto (8 bit, quantized) would show banding in slow gradients;
     * a fine noise (+-NOISE_LEVELS levels, identical on R/G/B so luminance only, in 2x2 blocks)
     * breaks the bands and survives compression and color subsampling.
     */
    private fun buildBackground(w: Int, h: Int): Bitmap {
        val noiseLevels = 3f
        val stops = floatArrayOf(0f, 0.38f, 0.62f, 1f)
        val cols = arrayOf(
            floatArrayOf(0x04.toFloat(), 0x09.toFloat(), 0x16.toFloat()),
            floatArrayOf(0x0A.toFloat(), 0x22.toFloat(), 0x50.toFloat()),
            floatArrayOf(0x0A.toFloat(), 0x12.toFloat(), 0x26.toFloat()),
            floatArrayOf(0x03.toFloat(), 0x05.toFloat(), 0x0B.toFloat())
        )
        val rnd = java.util.Random(1234L)
        val nw = (w + 1) / 2
        val nh = (h + 1) / 2
        // triangular noise (sum of two uniforms): more natural than uniform noise
        val noise = FloatArray(nw * nh) { (rnd.nextFloat() + rnd.nextFloat() - 1f) * noiseLevels }
        val pix = IntArray(w * h)
        for (y in 0 until h) {
            val t = y.toFloat() / max(1, h - 1)
            var seg = 0
            while (seg < 2 && t > stops[seg + 1]) seg++
            val k = ((t - stops[seg]) / (stops[seg + 1] - stops[seg])).coerceIn(0f, 1f)
            val r = cols[seg][0] + (cols[seg + 1][0] - cols[seg][0]) * k
            val g = cols[seg][1] + (cols[seg + 1][1] - cols[seg][1]) * k
            val b = cols[seg][2] + (cols[seg + 1][2] - cols[seg][2]) * k
            val row = (y / 2) * nw
            for (x in 0 until w) {
                val n = noise[row + x / 2]
                val rr = (r + n + 0.5f).toInt().coerceIn(0, 255)
                val gg = (g + n + 0.5f).toInt().coerceIn(0, 255)
                val bb = (b + n + 0.5f).toInt().coerceIn(0, 255)
                pix[y * w + x] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
            }
        }
        return Bitmap.createBitmap(pix, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun drawBackground(c: Canvas) {
        if (bgBitmap == null || bgW != sw || bgH2 != sh) {
            bgBitmap?.recycle()
            bgBitmap = buildBackground(sw, sh)
            bgW = sw
            bgH2 = sh
        }
        bgBitmap?.let { c.drawBitmap(it, 0f, 0f, null) }

        val star = fill(0xFFCFD8FF.toInt())
        for (i in 0 until 30) {
            c.drawCircle(stars[2 * i] * sw, stars[2 * i + 1] * sh * 0.38f, 1.2f + (i % 3) * 0.5f, star)
        }
        path.reset()
        for (i in mtX.indices) {
            val x = mtX[i] / 1280f * sw
            val y = mtY[i] / 720f * sh
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.lineTo(sw.toFloat(), 320f / 720f * sh)
        path.lineTo(0f, 320f / 720f * sh)
        path.close()
        c.drawPath(path, fill(0xEB0A1226.toInt()))
    }

    private fun drawStatus(c: Canvas) {
        if (SensorHub.hasFix) {
            text(c, "GPS OK", 36f, 48f, 18f, GOLD, bold = true, spacing = 3f, align = Paint.Align.LEFT)
        } else {
            text(c, "NO GPS FIX", 36f, 48f, 18f, RED, bold = true, spacing = 3f, align = Paint.Align.LEFT)
        }
    }

    // ======================================================================================
    // AMBIENT screen
    // ======================================================================================

    private fun drawTemp(c: Canvas) {
        val t = SensorHub.tempC
        c.drawArc(arcRect(140f), 135f, 270f, false, stroke(TRACK, 12f, Paint.Cap.ROUND))
        if (t != null) {
            val frac = ((t + 20f) / 70f).coerceIn(0f, 1f)
            if (frac > 0.004f) {
                c.drawArc(arcRect(140f), 135f, 270f * frac, false, stroke(GOLD, 12f, Paint.Cap.ROUND))
            }
        }
        val tk = stroke(0xFF3A4152.toInt(), 2f)
        for (i in 0..27) radial(c, 135f + i * 10f, 112f, 124f, tk)

        text(c, "OUTSIDE", 0f, -82f, 18f, GOLD, spacing = 4f)
        val big = if (t == null) "--" else t.roundToInt().toString()
        val nw = measure(big, 96f)
        // digits centered exactly in the dial (horizontally and vertically); the unit sits to the right without shifting them
        text(c, big, 0f, 34f, 96f, TEXT)
        text(c, "°C", nw / 2f + 6f, -8f, 34f, GOLD, align = Paint.Align.LEFT)
        // scale ends centered at the end of the track (at 135° and 45°), shifted inward
        text(c, "-20", -68f, 74f, 18f, DIM)
        text(c, "50", 68f, 74f, 18f, DIM)
    }

    private val cardinals8 = arrayOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")

    private fun labelAt(c: Canvas, s: String, deg: Float, size: Float, color: Int, bold: Boolean) {
        c.save()
        c.rotate(deg)
        text(c, s, 0f, -108f, size, color, bold = bold)
        c.restore()
    }

    private fun drawCompass(c: Canvas) {
        val hub = SensorHub
        val hd = headDamper.value
        val has = hub.heading != null

        c.save()
        c.rotate(-hd)
        ticks(c, 146f, 10f, 72, 2f, TICK)
        ticks(c, 140f, 18f, 12, 3.5f, TEXT)
        labelAt(c, "N", 0f, 30f, RED, true)
        labelAt(c, "E", 90f, 30f, TEXT, true)
        labelAt(c, "S", 180f, 30f, TEXT, true)
        labelAt(c, "O", 270f, 30f, TEXT, true)
        labelAt(c, "NE", 45f, 18f, MUTED, false)
        labelAt(c, "SE", 135f, 18f, MUTED, false)
        labelAt(c, "SO", 225f, 18f, MUTED, false)
        labelAt(c, "NO", 315f, 18f, MUTED, false)
        c.restore()

        c.drawCircle(0f, 0f, 96f, stroke(0xFF3A4152.toInt(), 2f))
        path.reset()
        path.moveTo(0f, -130f); path.lineTo(-12f, -156f); path.lineTo(12f, -156f); path.close()
        c.drawPath(path, fill(GOLD))

        val deg = hd.roundToInt() % 360
        val num = if (has) deg.toString() else "---"
        val nw = measure(num, 64f)
        val col = if (has) TEXT else DIM
        // digits centered in the dial; the ° symbol sits to the right without shifting them
        text(c, num, 0f, 23f, 64f, col)
        text(c, "°", nw / 2f + 2f, 23f, 64f, col, align = Paint.Align.LEFT)
    }

    private fun drawClock(c: Canvas) {
        cal.timeInMillis = System.currentTimeMillis()
        val day = cal.get(Calendar.DAY_OF_YEAR)
        if (day != lastDay) {
            lastDay = day
            dateStr = dateFmt.format(cal.time).uppercase(Locale.ENGLISH)
        }
        val s = cal.get(Calendar.SECOND) + cal.get(Calendar.MILLISECOND) / 1000f
        val m = cal.get(Calendar.MINUTE) + s / 60f
        val h = cal.get(Calendar.HOUR) + m / 60f

        ticks(c, 146f, 8f, 60, 2f, TICK)
        ticks(c, 140f, 18f, 12, 4f, TEXT)
        text(c, dateStr, 0f, 80f, 18f, MUTED, spacing = 3f)

        c.save(); c.rotate(h * 30f)
        c.drawLine(0f, 0f, 0f, -78f, stroke(TEXT, 9f, Paint.Cap.ROUND))
        c.restore()
        c.save(); c.rotate(m * 6f)
        c.drawLine(0f, 0f, 0f, -126f, stroke(TEXT, 5f, Paint.Cap.ROUND))
        c.restore()
        c.save(); c.rotate(s * 6f)
        c.drawLine(0f, 24f, 0f, -120f, stroke(GOLD, 2f, Paint.Cap.ROUND))
        c.restore()
        c.drawCircle(0f, 0f, 9f, fill(GOLD))
    }

    // ======================================================================================
    // ATTITUDE screen
    // ======================================================================================

    private fun drawAltimeter(c: Canvas) {
        val hub = SensorHub
        c.save()
        c.clipPath(clip)
        c.drawPath(waveFill, fill(BROWN))
        c.drawPath(waveLine, stroke(GOLD, 3f))
        c.restore()

        text(c, "ALTITUDE", 0f, -100f, 18f, GOLD, spacing = 4f)
        val has = hub.altitude != null
        val big = if (has) altDamper.value.roundToInt().toString() else "---"
        val nw = measure(big, 68f)
        val uw = measure("m", 30f)
        val x0 = -(nw + 10f + uw) / 2f
        text(c, big, x0, -34f, 68f, if (has) TEXT else DIM, align = Paint.Align.LEFT)
        text(c, "m", x0 + nw + 10f, -34f, 30f, GOLD, align = Paint.Align.LEFT)

        val pr = hub.pressureAvg
        text(c, if (pr != null) "${pr.roundToInt()} hPa" else "--- hPa", 0f, 58f, 40f, if (pr != null) TEXT else DIM)
        text(c, "BAROMETER", 0f, 90f, 18f, GOLD, spacing = 4f)
    }

    private fun drawTilt(c: Canvas, isRoll: Boolean) {
        val ok = SensorHub.tiltAvailable
        val value = if (isRoll) rollDamper.value else pitchDamper.value
        val shown = value.coerceIn(-40f, 40f)

        // Artificial horizon: terrain (color + line) and vehicle rotate together, as a single body.
        // Roll: car seen from behind, right side down = clockwise.
        // Pitch: car seen from the side, nose to the right, nose up = counterclockwise.
        c.save()
        c.clipPath(clip)
        c.rotate(if (isRoll) shown else -shown)
        rect.set(-400f, 0f, 400f, 400f)
        c.drawRect(rect, fill(BROWN))
        c.drawLine(-400f, 0f, 400f, 0f, stroke(GOLD, 3f))
        if (isRoll) drawCarRear(c) else drawCarSide(c)
        c.restore()

        // fixed scale -40..+40 on both sides
        val minor = stroke(TICK, 2f)
        for (side in intArrayOf(1, -1)) {
            var a = -40
            while (a <= 40) {
                if (a % 20 != 0) tiltTick(c, side, a.toFloat(), 145f, 155f, minor)
                a += 5
            }
        }
        val major = stroke(TEXT, 3.5f)
        for (side in intArrayOf(1, -1)) {
            var a = -40
            while (a <= 40) {
                tiltTick(c, side, a.toFloat(), 143f, 157f, major)
                a += 20
            }
        }
        for (side in intArrayOf(1, -1)) {
            for (a in intArrayOf(-40, -20, 0, 20, 40)) {
                val rad = Math.toRadians(a.toDouble())
                val x = side * cos(rad).toFloat() * 120f
                val y = sin(rad).toFloat() * 120f + 6f
                text(c, abs(a).toString(), x, y, 16f, if (a == 0) MUTED else TEXT)
            }
        }

        val label = if (!ok) "--°" else {
            val v = value.roundToInt()
            if (v == 0) "0°" else String.format(Locale.US, "%+d°", v)
        }
        text(c, label, 0f, 66f, 60f, if (ok) TEXT else DIM)
    }

    private fun tiltTick(c: Canvas, side: Int, deg: Float, r0: Float, r1: Float, paint: Paint) {
        val a = Math.toRadians(deg.toDouble())
        val ca = cos(a).toFloat()
        val sa = sin(a).toFloat()
        c.drawLine(side * ca * r0, sa * r0, side * ca * r1, sa * r1, paint)
    }

    private val TIRE = 0xFF3A4458.toInt()

    /** Willys Jeep seen from behind: wheels under the body, jerry can with X on the left, spare wheel on the right, bumper with spikes. */
    private fun drawCarRear(c: Canvas) {
        val dark = 0xFF252C3B.toInt()
        // windshield in two halves, visible above the body
        rect.set(-42f, -96f, 42f, -58f)
        c.drawRoundRect(rect, 3f, 3f, fill(GLASS))
        c.drawRoundRect(rect, 3f, 3f, stroke(CAR2, 4f))
        c.drawLine(0f, -96f, 0f, -58f, stroke(CAR2, 3f))
        // wheels: under the body, symmetric, notched tread
        for (side in intArrayOf(-1, 1)) {
            rect.set(if (side < 0) -48f else 26f, -36f, if (side < 0) -26f else 48f, 0f)
            c.drawRoundRect(rect, 5f, 5f, fill(TIRE))
            val tread = stroke(dark, 2f)
            var y = -30f
            while (y < -2f) {
                c.drawLine(rect.left + 2f, y, rect.right - 2f, y, tread)
                y += 7f
            }
        }
        // raised rear panel with upper edge
        rect.set(-52f, -62f, 52f, -26f); c.drawRoundRect(rect, 4f, 4f, fill(CAR))
        rect.set(-52f, -62f, 52f, -56f); c.drawRoundRect(rect, 3f, 3f, fill(CAR2))
        // jerry can on the left with embossed X
        rect.set(-44f, -54f, -24f, -30f); c.drawRoundRect(rect, 2f, 2f, fill(0xFFB9B39E.toInt()))
        val cross = stroke(0xFF7A745F.toInt(), 2f)
        c.drawLine(-42f, -52f, -26f, -32f, cross)
        c.drawLine(-26f, -52f, -42f, -32f, cross)
        // spare wheel on the right
        c.drawCircle(16f, -44f, 15f, fill(TIRE))
        c.drawCircle(16f, -44f, 15f, stroke(dark, 2f))
        c.drawCircle(16f, -44f, 8f, fill(CAR2))
        c.drawCircle(16f, -44f, 3f, fill(TIRE))
        // headlights
        rect.set(-50f, -40f, -45f, -32f); c.drawRoundRect(rect, 2f, 2f, fill(RED))
        rect.set(45f, -40f, 50f, -32f); c.drawRoundRect(rect, 2f, 2f, fill(RED))
        // bumper with two spikes
        rect.set(-56f, -28f, 56f, -21f); c.drawRoundRect(rect, 2f, 2f, fill(CAR2))
        rect.set(-36f, -32f, -29f, -21f); c.drawRoundRect(rect, 2f, 2f, fill(CAR2))
        rect.set(29f, -32f, 36f, -21f); c.drawRoundRect(rect, 2f, 2f, fill(CAR2))
    }

    /** Willys Jeep seen from the side, nose to the right: tall tub, open cabin, flat hood, upright windshield. */
    private fun drawCarSide(c: Canvas) {
        val arch = fill(0xFF222A3C.toInt())
        path.reset()
        path.moveTo(-84f, -22f); path.lineTo(-84f, -62f); path.lineTo(-6f, -62f)
        path.lineTo(-6f, -48f); path.lineTo(20f, -48f); path.lineTo(20f, -58f)
        path.lineTo(74f, -58f); path.lineTo(80f, -50f); path.lineTo(80f, -22f); path.close()
        c.drawPath(path, fill(CAR))
        // wheel arch
        c.drawCircle(-48f, -18f, 22f, arch)
        c.drawCircle(52f, -18f, 22f, arch)
        // upright windshield, tilted slightly backward
        c.drawLine(22f, -58f, 17f, -96f, stroke(CAR2, 4f, Paint.Cap.ROUND))
        // seat backrest
        rect.set(-34f, -78f, -24f, -50f); c.drawRoundRect(rect, 3f, 3f, fill(CAR2))
        // jerry can at the rear
        rect.set(-93f, -58f, -84f, -32f); c.drawRoundRect(rect, 2f, 2f, fill(CAR2))
        // round front headlight
        c.drawCircle(74f, -48f, 4.5f, fill(0xFFFFE9A8.toInt()))
        // wheels
        for (x in floatArrayOf(-48f, 52f)) {
            c.drawCircle(x, -18f, 18f, fill(TIRE))
            c.drawCircle(x, -18f, 9f, fill(CAR2))
            c.drawCircle(x, -18f, 3f, fill(TIRE))
        }
    }

    // ======================================================================================
    // Bottom cards, zero button and messages
    // ======================================================================================

    private val tabH = 46f
    private val tabBottom = 12f

    /** Computes the rectangles of the cards and of the zero button in the design space (width w, height h). */
    private fun layoutBar(w: Float, h: Float, m: Mode) {
        val top = h - tabBottom - tabH
        if (m == Mode.FULL) {
            // on narrow areas, compresses cards and button so the zero button does not go off screen
            val f = min(1f, (w / 2f - 16f) / 456f)
            val tabW = 200f * f
            val left = w / 2f - tabW - 2f
            tabAmb.set(left, top, left + tabW, top + tabH)
            tabAss.set(left + tabW + 4f, top, left + tabW * 2f + 4f, top + tabH)
            val zl = tabAss.right + 36f * f
            zeroBtn.set(zl, top, zl + 220f * f, top + tabH)
        } else {
            // partial modes: no cards, only the zero button if present, centered
            tabAmb.setEmpty()
            tabAss.setEmpty()
            val zw = min(220f, w - 28f)
            zeroBtn.set(w / 2f - zw / 2f, top, w / 2f + zw / 2f, top + tabH)
        }
    }

    private fun drawTabs(c: Canvas, pos: Float) {
        val amb = pos < 0.5f
        c.drawRect(tabAmb, fill(if (amb) 0xFF3B3A36.toInt() else 0xFF25262B.toInt()))
        c.drawRect(tabAss, fill(if (!amb) 0xFF3B3A36.toInt() else 0xFF25262B.toInt()))
        text(c, "AMBIENT", tabAmb.centerX(), tabAmb.centerY() + 7f, 20f, if (amb) GOLD else TEXT, bold = amb, spacing = 3f)
        text(c, "ATTITUDE", tabAss.centerX(), tabAss.centerY() + 7f, 20f, if (!amb) GOLD else TEXT, bold = !amb, spacing = 3f)
        // gold bar that follows the page scrolling
        val ix = tabAmb.left + (tabAss.left - tabAmb.left) * pos
        rect.set(ix, tabAmb.bottom - 3f, ix + tabAmb.width(), tabAmb.bottom)
        c.drawRect(rect, fill(GOLD))
    }

    /** Zero button: part of the Attitude page and scrolls together with it. */
    private fun drawZeroButton(c: Canvas) {
        val r = zeroBtn.height() / 2f
        c.drawRoundRect(zeroBtn, r, r, fill(0xFF1A1C22.toInt()))
        c.drawRoundRect(zeroBtn, r, r, stroke(GOLD, 2f))
        val ix = zeroBtn.left + 32f
        val iy = zeroBtn.centerY()
        c.drawCircle(ix, iy, 6f, stroke(GOLD, 2.2f))
        val ln = stroke(GOLD, 2.2f, Paint.Cap.ROUND)
        c.drawLine(ix, iy - 11f, ix, iy - 5f, ln)
        c.drawLine(ix, iy + 5f, ix, iy + 11f, ln)
        c.drawLine(ix - 11f, iy, ix - 5f, iy, ln)
        c.drawLine(ix + 5f, iy, ix + 11f, iy, ln)
        text(c, "ZERO 0°", ix + 22f, iy + 7f, 20f, GOLD, bold = true, spacing = 3f, align = Paint.Align.LEFT)
    }

    private fun drawFlash(c: Canvas, dw: Float, h: Float) {
        val msg = flashText ?: return
        val remain = flashUntil - SystemClock.uptimeMillis()
        if (remain <= 0L) return
        val f = min(1f, remain / 500f)
        var sz = 22f
        val mw = measure(msg, sz, true, 2f)
        if (mw + 56f > dw - 20f) sz = sz * (dw - 76f) / mw   // long message on a narrow area: reduces the font size
        val w = measure(msg, sz, true, 2f) + 56f
        val bottom = h - tabBottom - tabH - 10f
        rect.set(dw / 2f - w / 2f, bottom - 42f, dw / 2f + w / 2f, bottom)
        c.drawRoundRect(rect, 21f, 21f, fill(withAlpha(0xFF10141C.toInt(), f)))
        c.drawRoundRect(rect, 21f, 21f, stroke(withAlpha(if (flashOk) GREEN else RED, f), 2f))
        text(c, msg, dw / 2f, bottom - 13f, sz, withAlpha(if (flashOk) GREEN else RED, f), bold = true, spacing = 2f)
    }
}
