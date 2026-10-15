package com.justtrack.app

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GestureActionTest {

    private val allActions: List<GestureAction> = listOf(
        GestureAction.None,
        GestureAction.VolumeSet(0.1f),
        GestureAction.PlayPause,
        GestureAction.NextTrack,
        GestureAction.PrevTrack,
        GestureAction.SwipeLeft,
        GestureAction.SwipeRight,
        GestureAction.TapScreen,
        GestureAction.GoBack,
        GestureAction.GoHome,
        GestureAction.RecentApps,
        GestureAction.ScreenScroll(0.1f, 0.2f),
        GestureAction.TapAt(0.3f, 0.4f),
        GestureAction.PreviousApp,
        GestureAction.CursorMove(0.5f, 0.6f),
        GestureAction.ModeSwitch
    )

    @Test
    fun `sealed interface has all expected subtypes`() {
        assertThat(allActions).hasSize(16)
        assertThat(allActions.map { it::class }.toSet()).hasSize(16)
    }

    @Test
    fun `data objects are singletons`() {
        assertThat(GestureAction.None).isSameInstanceAs(GestureAction.None)
        assertThat(GestureAction.PlayPause).isSameInstanceAs(GestureAction.PlayPause)
        assertThat(GestureAction.SwipeLeft).isSameInstanceAs(GestureAction.SwipeLeft)
        assertThat(GestureAction.GoBack).isSameInstanceAs(GestureAction.GoBack)
        assertThat(GestureAction.PreviousApp).isSameInstanceAs(GestureAction.PreviousApp)
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
        allActions.forEach { assertThat(it).isInstanceOf(GestureAction::class.java) }
    }

    @Test
    fun `ScreenScroll carries delta values`() {
        val action = GestureAction.ScreenScroll(0.1f, -0.2f)
        assertThat(action.deltaX).isWithin(0.001f).of(0.1f)
        assertThat(action.deltaY).isWithin(0.001f).of(-0.2f)
    }

    @Test
    fun `ScreenScroll equality based on values`() {
        assertThat(GestureAction.ScreenScroll(0.1f, 0.2f)).isEqualTo(GestureAction.ScreenScroll(0.1f, 0.2f))
        assertThat(GestureAction.ScreenScroll(0.1f, 0.2f)).isNotEqualTo(GestureAction.ScreenScroll(0.3f, 0.2f))
    }

    @Test
    fun `TapAt carries coordinate values`() {
        val action = GestureAction.TapAt(0.3f, 0.7f)
        assertThat(action.x).isWithin(0.001f).of(0.3f)
        assertThat(action.y).isWithin(0.001f).of(0.7f)
    }

    @Test
    fun `TapAt equality based on values`() {
        assertThat(GestureAction.TapAt(0.1f, 0.2f)).isEqualTo(GestureAction.TapAt(0.1f, 0.2f))
        assertThat(GestureAction.TapAt(0.1f, 0.2f)).isNotEqualTo(GestureAction.TapAt(0.3f, 0.2f))
    }

    @Test
    fun `CursorMove carries coordinate values`() {
        val action = GestureAction.CursorMove(0.4f, 0.8f)
        assertThat(action.x).isWithin(0.001f).of(0.4f)
        assertThat(action.y).isWithin(0.001f).of(0.8f)
    }

    @Test
    fun `CursorMove equality based on values`() {
        assertThat(GestureAction.CursorMove(0.1f, 0.2f)).isEqualTo(GestureAction.CursorMove(0.1f, 0.2f))
        assertThat(GestureAction.CursorMove(0.1f, 0.2f)).isNotEqualTo(GestureAction.CursorMove(0.1f, 0.3f))
    }

    @Test
    fun `ControlMode has MEDIA and CONTROL`() {
        assertThat(ControlMode.values()).asList()
            .containsExactly(ControlMode.MEDIA, ControlMode.CONTROL)
    }
}
