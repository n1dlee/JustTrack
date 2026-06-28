package com.justtrack.app

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GestureActionTest {

    @Test
    fun `enum has six values`() {
        val values = GestureAction.values()
        assertThat(values).hasLength(6)
        assertThat(values.map { it.name }).containsExactly(
            "VOLUME_UP", "VOLUME_DOWN", "PLAY_PAUSE", "NEXT_TRACK", "PREV_TRACK", "NONE"
        )
    }

    @Test
    fun `values are distinct`() {
        val values = GestureAction.values()
        assertThat(values.toSet()).hasSize(6)
    }
}
