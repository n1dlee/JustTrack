package com.justtrack.app

import kotlin.math.PI
import kotlin.math.abs

/**
 * One Euro filter (Casiez et al., 2012): an adaptive low-pass filter that smooths
 * jitter when the signal moves slowly and lets fast motion through with little lag.
 * Used for the fist cursor so it does not shake while the hand is held still.
 */
class OneEuroFilter(
    private val minCutoffHz: Float = 1.0f,
    private val beta: Float = 5.0f,
    private val derivativeCutoffHz: Float = 1.0f
) {
    private var hasPrevious = false
    private var previousValue = 0f
    private var previousDerivative = 0f
    private var previousTimeMs = 0L

    fun reset() {
        hasPrevious = false
    }

    fun filter(value: Float, timeMs: Long): Float {
        if (!hasPrevious) {
            hasPrevious = true
            previousValue = value
            previousDerivative = 0f
            previousTimeMs = timeMs
            return value
        }
        val dt = (timeMs - previousTimeMs).coerceAtLeast(1L) / 1000f
        previousTimeMs = timeMs

        val rawDerivative = (value - previousValue) / dt
        val derivativeAlpha = alpha(derivativeCutoffHz, dt)
        previousDerivative = derivativeAlpha * rawDerivative + (1f - derivativeAlpha) * previousDerivative

        val cutoff = minCutoffHz + beta * abs(previousDerivative)
        val valueAlpha = alpha(cutoff, dt)
        previousValue = valueAlpha * value + (1f - valueAlpha) * previousValue
        return previousValue
    }

    private fun alpha(cutoffHz: Float, dt: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoffHz)
        return 1f / (1f + tau / dt)
    }
}
