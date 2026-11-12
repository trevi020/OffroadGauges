package com.offroadgauges.core

import kotlin.math.max

/**
 * Second-order damper (critically damped, "SmoothDamp" style).
 * Simulates the needle of a glycerin-damped mechanical instrument:
 * no jerking, no overshoot, settles in about 3-4 times smoothTime.
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

    /** Variant for 0..360 angles: always follows the shortest arc. */
    fun updateAngle(target: Float, dt: Float, smoothTime: Float): Float {
        val delta = ((target - value + 540f) % 360f + 360f) % 360f - 180f
        update(value + delta, dt, smoothTime)
        value = ((value % 360f) + 360f) % 360f
        return value
    }
}
