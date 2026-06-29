package com.justtrack.app

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GestureActionTest {

    @Test
    fun `sealed interface has all expected subtypes`() {
        val actions: List<GestureAction> = listOf(
            GestureAction.None,
            GestureAction.VolumeSet(0.1f),
            GestureAction.PlayPause,
            GestureAction.NextTrack,
            GestureAction.PrevTrack,
            GestureAction.SwipeUp,
            GestureAction.SwipeDown,
            GestureAction.SwipeLeft,
            GestureAction.SwipeRight,
            GestureAction.TapScreen,
            GestureAction.DoubleTapScreen,
            GestureAction.ModeSwitch
        )
        assertThat(actions).hasSize(12)
    }

    @Test
    fun `data objects are singletons`() {
        assertThat(GestureAction.None).isSameInstanceAs(GestureAction.None)
        assertThat(GestureAction.PlayPause).isSameInstanceAs(GestureAction.PlayPause)
        assertThat(GestureAction.SwipeUp).isSameInstanceAs(GestureAction.SwipeUp)
    }

    @Test
    fun `VolumeSet carries delta value`() {
        val action = GestureAction.VolumeSet(0.25f)
        assertThat(action.deltaY).isWithin(0.001f).of(0.25f)
    }

    @Test
    fun `VolumeSet equality based on value`() {
        assertThat(GestureAction.VolumeSet(0.5f)).isEqualTo(GestureAction.VolumeSet(0.5f))
        assertThat(GestureAction.VolumeSet(0.5f)).isNotEqualTo(GestureAction.VolumeSet(0.3f))
    }

    @Test
    fun `all actions are GestureAction instances`() {
        val actions: List<GestureAction> = listOf(
            GestureAction.None,
            GestureAction.VolumeSet(-0.1f),
            GestureAction.PlayPause,
            GestureAction.NextTrack,
            GestureAction.PrevTrack,
            GestureAction.SwipeUp,
            GestureAction.SwipeDown,
            GestureAction.SwipeLeft,
            GestureAction.SwipeRight,
            GestureAction.TapScreen,
            GestureAction.DoubleTapScreen,
            GestureAction.ModeSwitch
        )
        actions.forEach { assertThat(it).isInstanceOf(GestureAction::class.java) }
    }

    @Test
    fun `ControlMode has MEDIA and SCREEN`() {
        assertThat(ControlMode.values()).asList().containsExactly(ControlMode.MEDIA, ControlMode.SCREEN)
    }
}
