package com.justtrack.app

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ForegroundAppTrackerTest {

    private val ignored = setOf("com.android.systemui", "com.samsung.keyboard")
    private val tracker = ForegroundAppTracker { it !in ignored }

    @Test
    fun `starts with no apps`() {
        assertThat(tracker.current).isNull()
        assertThat(tracker.previous).isNull()
    }

    @Test
    fun `switching apps remembers the previous one`() {
        tracker.onForegroundPackage("com.youtube")
        tracker.onForegroundPackage("org.telegram")
        assertThat(tracker.current).isEqualTo("org.telegram")
        assertThat(tracker.previous).isEqualTo("com.youtube")
    }

    @Test
    fun `A to B to A toggles between the two`() {
        tracker.onForegroundPackage("A")
        tracker.onForegroundPackage("B")
        tracker.onForegroundPackage("A")
        assertThat(tracker.current).isEqualTo("A")
        assertThat(tracker.previous).isEqualTo("B")
    }

    @Test
    fun `repeated events from the same app do not erase history`() {
        tracker.onForegroundPackage("A")
        tracker.onForegroundPackage("B")
        tracker.onForegroundPackage("B")
        assertThat(tracker.previous).isEqualTo("A")
    }

    @Test
    fun `system UI and keyboards are ignored`() {
        tracker.onForegroundPackage("A")
        tracker.onForegroundPackage("B")
        tracker.onForegroundPackage("com.android.systemui")
        tracker.onForegroundPackage("com.samsung.keyboard")
        assertThat(tracker.current).isEqualTo("B")
        assertThat(tracker.previous).isEqualTo("A")
    }

    @Test
    fun `null and empty package names are ignored`() {
        tracker.onForegroundPackage("A")
        tracker.onForegroundPackage(null)
        tracker.onForegroundPackage("")
        assertThat(tracker.current).isEqualTo("A")
    }

    @Test
    fun `clear forgets everything`() {
        tracker.onForegroundPackage("A")
        tracker.onForegroundPackage("B")
        tracker.clear()
        assertThat(tracker.current).isNull()
        assertThat(tracker.previous).isNull()
    }
}
