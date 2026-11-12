package com.offroadgauges.core

import kotlin.math.max

/**
 * Smorzatore del secondo ordine (critically damped, stile "SmoothDamp").
 * Simula l'ago di uno strumento meccanico a bagno di glicerina:
 * nessun scatto, nessun overshoot, si assesta in circa 3-4 volte smoothTime.
 */
class Damper(var value: Float = 0f) {
    private var velocity = 0f

    fun update(target: Float, dt: Float, smoothTime: Float): Float {
        val st = max(0.0001f, smoothTime)
        val omega = 2f / st
        val x = omega * dt
        val e = 1f / (1f + x + 0.48f * x * x + 0.235f * x * x * x)
        val change = value - target
        val temp = (velocity + omega * change) * dt
        velocity = (velocity - omega * temp) * e
        value = target + (change + temp) * e
        return value
    }

    /** Variante per angoli 0..360: segue sempre l'arco piu' corto. */
    fun updateAngle(target: Float, dt: Float, smoothTime: Float): Float {
        val delta = ((target - value + 540f) % 360f + 360f) % 360f - 180f
        update(value + delta, dt, smoothTime)
        value = ((value % 360f) + 360f) % 360f
        return value
    }
}
