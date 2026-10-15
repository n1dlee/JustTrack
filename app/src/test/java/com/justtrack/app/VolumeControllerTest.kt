package com.justtrack.app

import android.content.Context
import android.media.AudioManager
import android.os.Vibrator
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Before
import org.junit.Test

class VolumeControllerTest {

    private lateinit var audioManager: AudioManager
    private lateinit var vibrator: Vibrator
    private lateinit var controller: VolumeController

    @Before
    fun setUp() {
        audioManager = mockk(relaxed = true)
        vibrator = mockk(relaxed = true)
        val context = mockk<Context> {
            every { getSystemService(Context.AUDIO_SERVICE) } returns audioManager
            @Suppress("DEPRECATION")
            every { getSystemService(Context.VIBRATOR_SERVICE) } returns vibrator
        }
        controller = VolumeController(context)
        controller.hapticEnabled = false
    }

    // ============================
    // Smooth volume
    // ============================

    @Test
    fun `beginSmoothVolume captures current volume`() {
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 7
        controller.beginSmoothVolume()
        every { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } returns 15
        controller.setVolumeByDelta(0f)
        verify(exactly = 0) { audioManager.setStreamVolume(any(), any(), any()) }
    }

    @Test
    fun `setVolumeByDelta increases volume on positive delta`() {
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 5
        every { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } returns 15
        controller.beginSmoothVolume()
        controller.setVolumeByDelta(0.2f)
        verify { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, any(), AudioManager.FLAG_SHOW_UI) }
    }

    @Test
    fun `setVolumeByDelta decreases volume on negative delta`() {
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 10
        every { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } returns 15
        controller.beginSmoothVolume()
        controller.setVolumeByDelta(-0.2f)
        verify { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, any(), AudioManager.FLAG_SHOW_UI) }
    }

    @Test
    fun `setVolumeByDelta clamps to zero`() {
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 2
        every { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } returns 15
        controller.beginSmoothVolume()
        controller.setVolumeByDelta(-1.0f)
        verify { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI) }
    }

    @Test
    fun `setVolumeByDelta clamps to max`() {
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 10
        every { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } returns 15
        controller.beginSmoothVolume()
        controller.setVolumeByDelta(1.0f)
        verify { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 15, AudioManager.FLAG_SHOW_UI) }
    }

    @Test
    fun `setVolumeByDelta skips set when volume unchanged`() {
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 7
        every { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } returns 15
        controller.beginSmoothVolume()
        controller.setVolumeByDelta(0f)
        verify(exactly = 0) { audioManager.setStreamVolume(any(), any(), any()) }
    }

    // ============================
    // Media key dispatch
    // ============================

    @Test
    fun `playPause dispatches two media key events`() {
        controller.playPause()
        verify(exactly = 2) { audioManager.dispatchMediaKeyEvent(any()) }
    }

    @Test
    fun `nextTrack dispatches two media key events`() {
        controller.nextTrack()
        verify(exactly = 2) { audioManager.dispatchMediaKeyEvent(any()) }
    }

    @Test
    fun `prevTrack dispatches two media key events`() {
        controller.prevTrack()
        verify(exactly = 2) { audioManager.dispatchMediaKeyEvent(any()) }
    }

    // ============================
    // getCurrentVolume / getMaxVolume
    // ============================

    @Test
    fun `getCurrentVolume returns stream volume`() {
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 7
        assertThat(controller.getCurrentVolume()).isEqualTo(7)
    }

    @Test
    fun `getMaxVolume returns stream max volume`() {
        every { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } returns 15
        assertThat(controller.getMaxVolume()).isEqualTo(15)
    }

    // ============================
    // Haptic feedback
    // ============================

    @Test
    fun `haptic vibrates on playPause when enabled`() {
        controller.hapticEnabled = true
        controller.playPause()
        verify { vibrator.vibrate(any<android.os.VibrationEffect>()) }
    }

    @Test
    fun `no vibration when haptic disabled`() {
        controller.hapticEnabled = false
        controller.playPause()
        verify(exactly = 0) { vibrator.vibrate(any<android.os.VibrationEffect>()) }
    }

    @Test
    fun `vibrateActivation vibrates with pattern`() {
        controller.vibrateActivation()
        verify { vibrator.vibrate(any<android.os.VibrationEffect>()) }
    }

    // ============================
    // dispatchAction
    // ============================

    @Test
    fun `dispatchAction VolumeSet calls setVolumeByDelta`() {
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 5
        every { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } returns 15
        controller.beginSmoothVolume()
        controller.dispatchAction(GestureAction.VolumeSet(0.2f))
        verify { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, any(), AudioManager.FLAG_SHOW_UI) }
    }

    @Test
    fun `dispatchAction PlayPause dispatches media key`() {
        controller.dispatchAction(GestureAction.PlayPause)
        verify(exactly = 2) { audioManager.dispatchMediaKeyEvent(any()) }
    }

    @Test
    fun `dispatchAction NextTrack dispatches media key`() {
        controller.dispatchAction(GestureAction.NextTrack)
        verify(exactly = 2) { audioManager.dispatchMediaKeyEvent(any()) }
    }

    @Test
    fun `dispatchAction PrevTrack dispatches media key`() {
        controller.dispatchAction(GestureAction.PrevTrack)
        verify(exactly = 2) { audioManager.dispatchMediaKeyEvent(any()) }
    }

    @Test
    fun `dispatchAction None does nothing`() {
        controller.dispatchAction(GestureAction.None)
        verify(exactly = 0) { audioManager.setStreamVolume(any(), any(), any()) }
        verify(exactly = 0) { audioManager.dispatchMediaKeyEvent(any()) }
    }

    @Test
    fun `dispatchAction ignores screen actions`() {
        controller.dispatchAction(GestureAction.SwipeLeft)
        controller.dispatchAction(GestureAction.TapScreen)
        verify(exactly = 0) { audioManager.setStreamVolume(any(), any(), any()) }
        verify(exactly = 0) { audioManager.dispatchMediaKeyEvent(any()) }
    }
}
