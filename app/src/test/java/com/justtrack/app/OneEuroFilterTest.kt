package com.justtrack.app

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs

class OneEuroFilterTest {

    @Test
    fun `first sample passes through unchanged`() {
        val filter = OneEuroFilter()
        assertThat(filter.filter(0.42f, 1000L)).isWithin(1e-6f).of(0.42f)
    }

    @Test
    fun `constant input stays constant`() {
        val filter = OneEuroFilter()
        var t = 0L
        repeat(20) {
            t += 33
            assertThat(filter.filter(0.5f, t)).isWithin(1e-6f).of(0.5f)
        }
    }

    @Test
    fun `small jitter is attenuated`() {
        val filter = OneEuroFilter()
        var t = 0L
        filter.filter(0.5f, t)
        var maxDeviation = 0f
        repeat(60) { i ->
            t += 33
            val noisy = 0.5f + if (i % 2 == 0) 0.01f else -0.01f
            maxDeviation = maxOf(maxDeviation, abs(filter.filter(noisy, t) - 0.5f))
        }
        assertThat(maxDeviation).isLessThan(0.01f)
    }

    @Test
    fun `fast motion is followed closely`() {
        val filter = OneEuroFilter()
        var t = 0L
        filter.filter(0.1f, t)
        var value = 0.1f
        var output = 0f
        repeat(15) {
            t += 33
            value += 0.05f // ~1.5 screen widths per second
            output = filter.filter(value, t)
        }
        assertThat(abs(output - value)).isLessThan(0.05f)
    }

    @Test
    fun `reset forgets history`() {
        val filter = OneEuroFilter()
        filter.filter(0.1f, 0L)
        filter.filter(0.2f, 33L)
        filter.reset()
        assertThat(filter.filter(0.9f, 66L)).isWithin(1e-6f).of(0.9f)
    }
}
