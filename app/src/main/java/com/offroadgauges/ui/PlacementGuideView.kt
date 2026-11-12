package com.offroadgauges.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Looping animation (7 s) showing how to place the phone:
 *  - on the left, top view: the phone arrives sideways, rotates and lines up with its long side
 *    parallel to the car's axis, top edge toward the windshield;
 *  - on the right, side view: the phone comes down and rests flat, the level returns to 0 degrees.
 */
class PlacementGuideView(context: Context) : View(context) {

    private val d = resources.displayMetrics.density
    private var t = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()

    private val gold = 0xFFD8B574.toInt()
    private val ink = 0xFFF2ECE0.toInt()
    private val muted = 0xFF9AA3B5.toInt()
    private val line = 0xFF5A6478.toInt()
    private val green = 0xFF4CD08A.toInt()

    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 7000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            t = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        anim.start()
    }

    override fun onDetachedFromWindow() {
        anim.cancel()
        super.onDetachedFromWindow()
    }

    private fun seg(x: Float, a: Float, b: Float) = ((x - a) / (b - a)).coerceIn(0f, 1f)
    private fun ease(x: Float) = x * x * (3f - 2f * x)
    private fun lerp(a: Float, b: Float, k: Float) = a + (b - a) * k

    private fun fill(color: Int): Paint {
        p.reset(); p.isAntiAlias = true; p.style = Paint.Style.FILL; p.color = color
        return p
    }

    private fun stroke(color: Int, w: Float, dashed: Boolean = false): Paint {
        p.reset(); p.isAntiAlias = true; p.style = Paint.Style.STROKE; p.color = color; p.strokeWidth = w
        p.strokeCap = Paint.Cap.ROUND
        if (dashed) p.pathEffect = android.graphics.DashPathEffect(floatArrayOf(6 * d, 5 * d), 0f)
        return p
    }

    private fun label(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, align: Paint.Align = Paint.Align.CENTER) {
        p.reset(); p.isAntiAlias = true; p.style = Paint.Style.FILL; p.color = color
        p.textSize = size * d; p.textAlign = align
        c.drawText(s, x, y, p)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        c.drawRoundRect(0f, 0f, w, h, 16 * d, 16 * d, fill(0xFF0F1420.toInt()))

        val capH = 34 * d
        val hp = h - capH
        val lw = w * 0.56f
        label(c, "TOP VIEW", lw / 2f, 18 * d, 11f, muted)
        label(c, "SIDE VIEW", lw + (w - lw) / 2f, 18 * d, 11f, muted)

        val settled = t > 0.66f
        val fade = 1f - seg(t, 0.93f, 1f)

        // ---------------- top view -----------------------
        val topY = 28 * d
        val availH = hp - topY - 4 * d
        val ch = availH
        val cw = min(lw * 0.50f, ch * 0.44f)
        val ccx = lw * 0.5f
        rect.set(ccx - cw / 2f, topY, ccx + cw / 2f, topY + ch)
        c.drawRoundRect(rect, cw * 0.3f, cw * 0.3f, fill(0xFF171D2B.toInt()))
        c.drawRoundRect(rect, cw * 0.3f, cw * 0.3f, stroke(line, 2 * d))
        // windshield and dashboard
        c.drawLine(ccx - cw * 0.38f, topY + ch * 0.26f, ccx + cw * 0.38f, topY + ch * 0.26f, stroke(line, 2 * d))
        c.drawLine(ccx - cw * 0.44f, topY + ch * 0.33f, ccx + cw * 0.44f, topY + ch * 0.33f, stroke(0xFF3A4152.toInt(), 1.5f * d))
        label(c, "WINDSHIELD", ccx, topY + ch * 0.17f, 9f, muted)
        label(c, "DRIVER", ccx, topY + ch * 0.92f, 9f, muted)

        // phone compartment / holder
        val trayCx = ccx
        val trayCy = topY + ch * 0.52f
        val trayW = cw * 0.36f
        val trayH = ch * 0.27f
        rect.set(trayCx - trayW / 2f, trayCy - trayH / 2f, trayCx + trayW / 2f, trayCy + trayH / 2f)
        c.drawRoundRect(rect, 6 * d, 6 * d, stroke(withA(gold, 0.8f), 1.5f * d, dashed = true))

        // phone: arrives sideways, rotates, lines up
        val pw = cw * 0.20f
        val ph = pw * 2.1f
        val move = ease(seg(t, 0.02f, 0.32f))
        val rot = 90f * (1f - ease(seg(t, 0.28f, 0.55f)))
        val px = lerp(lw * 0.93f, trayCx, move)
        val py = lerp(topY + ch * 0.80f, trayCy, move)
        val press = 1f - 0.07f * sin(PI.toFloat() * seg(t, 0.58f, 0.68f))

        c.saveLayerAlpha(RectF(0f, 0f, w, h), (255 * fade).roundToInt())
        c.save()
        c.translate(px, py)
        c.rotate(rot)
        c.scale(press, press)
        rect.set(-pw / 2f, -ph / 2f, pw / 2f, ph / 2f)
        c.drawRoundRect(rect, 5 * d, 5 * d, fill(0xFF222A3C.toInt()))
        c.drawRoundRect(rect, 5 * d, 5 * d, stroke(if (settled) green else ink, 2 * d))
        rect.set(-pw / 2f + 3 * d, -ph / 2f + 6 * d, pw / 2f - 3 * d, ph / 2f - 6 * d)
        c.drawRoundRect(rect, 3 * d, 3 * d, fill(0xFF0B1020.toInt()))
        // arrow: top edge of the phone
        path.reset()
        path.moveTo(0f, -ph / 2f + 9 * d)
        path.lineTo(-5 * d, -ph / 2f + 19 * d)
        path.lineTo(5 * d, -ph / 2f + 19 * d)
        path.close()
        c.drawPath(path, fill(gold))
        c.restore()
        c.restore()

        // green check mark when correctly placed
        if (settled) {
            val k = ease(seg(t, 0.66f, 0.74f)) * fade
            val cx = trayCx + trayW / 2f + 4 * d
            val cy = trayCy - trayH / 2f - 2 * d
            c.drawCircle(cx, cy, 10 * d * k, fill(withA(green, 1f)))
            if (k > 0.8f) {
                val ck = stroke(0xFF0B1020.toInt(), 2 * d)
                c.drawLine(cx - 4 * d, cy, cx - 1 * d, cy + 3 * d, ck)
                c.drawLine(cx - 1 * d, cy + 3 * d, cx + 5 * d, cy - 4 * d, ck)
            }
        }

        // ---------------- side view ---------------------
        val x0 = lw + 14 * d
        val x1 = w - 14 * d
        val sy = topY + availH * 0.58f
        val cxs = (x0 + x1) / 2f
        c.drawLine(x0, sy, x1, sy, stroke(line, 3 * d))
        label(c, "DRIVER", x0, sy + 16 * d, 9f, muted, Paint.Align.LEFT)
        label(c, "WINDSHIELD", x1, sy + 16 * d, 9f, muted, Paint.Align.RIGHT)

        val e = ease(seg(t, 0.30f, 0.62f))
        val ang = 24f * (1f - e)
        val lift = 26 * d * (1f - e)
        val len = (x1 - x0) * 0.78f
        val th = 7 * d
        c.saveLayerAlpha(RectF(0f, 0f, w, h), (255 * fade).roundToInt())
        c.save()
        c.translate(cxs, sy - th / 2f - lift)
        c.rotate(-ang)
        rect.set(-len / 2f, -th / 2f, len / 2f, th / 2f)
        c.drawRoundRect(rect, 3 * d, 3 * d, fill(0xFF222A3C.toInt()))
        c.drawRoundRect(rect, 3 * d, 3 * d, stroke(if (settled) green else ink, 2 * d))
        c.restore()
        c.restore()

        // level
        val ty = sy + 44 * d
        val tw = 120 * d
        rect.set(cxs - tw / 2f, ty - 8 * d, cxs + tw / 2f, ty + 8 * d)
        c.drawRoundRect(rect, 8 * d, 8 * d, fill(0xFF171D2B.toInt()))
        c.drawRoundRect(rect, 8 * d, 8 * d, stroke(line, 1.5f * d))
        c.drawLine(cxs - 7 * d, ty - 8 * d, cxs - 7 * d, ty + 8 * d, stroke(line, 1.5f * d))
        c.drawLine(cxs + 7 * d, ty - 8 * d, cxs + 7 * d, ty + 8 * d, stroke(line, 1.5f * d))
        val bx = cxs - (ang / 24f) * (tw * 0.38f)
        c.drawCircle(bx, ty, 6 * d, fill(if (settled) green else gold))
        label(c, "${ang.roundToInt()}°", cxs, ty + 30 * d, 20f, if (settled) green else ink)

        // caption
        val cap = when {
            t < 0.30f -> "Move the phone onto the mount"
            t < 0.60f -> "Rotate it: long side parallel to the car"
            else -> "Flat, screen up, top edge toward the windshield"
        }
        label(c, cap, w / 2f, h - 12 * d, 13f, ink)
    }

    private fun withA(color: Int, f: Float): Int {
        val a = ((color ushr 24) * f).roundToInt()
        return (color and 0x00FFFFFF) or (max(0, min(255, a)) shl 24)
    }
}
