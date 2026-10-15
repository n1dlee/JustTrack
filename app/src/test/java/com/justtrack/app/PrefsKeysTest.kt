package com.justtrack.app

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PrefsKeysTest {

    @Test
    fun `pinchThreshold increases with sensitivity`() {
        val low = PrefsKeys.pinchThreshold(1)
        val high = PrefsKeys.pinchThreshold(10)
        assertThat(high).isGreaterThan(low)
    }

    @Test
    fun `pinchThreshold at default sensitivity`() {
        val threshold = PrefsKeys.pinchThreshold(PrefsKeys.DEFAULT_SENSITIVITY)
        assertThat(threshold).isWithin(0.001f).of(0.20f)
    }

    @Test
    fun `moveThreshold decreases with sensitivity`() {
        val low = PrefsKeys.moveThreshold(1)
        val high = PrefsKeys.moveThreshold(10)
        assertThat(high).isLessThan(low)
    }

    @Test
    fun `moveThreshold at default sensitivity`() {
        val threshold = PrefsKeys.moveThreshold(PrefsKeys.DEFAULT_SENSITIVITY)
        assertThat(threshold).isWithin(0.001f).of(0.05f)
    }

    @Test
    fun `thresholds are positive at all valid sensitivities`() {
        for (s in 1..10) {
            assertThat(PrefsKeys.pinchThreshold(s)).isGreaterThan(0f)
            assertThat(PrefsKeys.moveThreshold(s)).isGreaterThan(0f)
        }
    }

    @Test
    fun `default main hand is both`() {
        assertThat(PrefsKeys.DEFAULT_MAIN_HAND).isEqualTo("both")
    }

    @Test
    fun `default auto start is false`() {
        assertThat(PrefsKeys.DEFAULT_AUTO_START).isFalse()
    }
}
