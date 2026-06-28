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

    @Test
    fun `volumeUp calls adjustStreamVolume with ADJUST_RAISE`() {
        controller.volumeUp()
        verify {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
        }
    }

    @Test
    fun `volumeDown calls adjustStreamVolume with ADJUST_LOWER`() {
        controller.volumeDown()
        verify {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
        }
    }

    @Test
    fun `volumeUp uses STREAM_MUSIC not STREAM_RING`() {
        controller.volumeUp()
        verify { audioManager.adjustStreamVolume(eq(AudioManager.STREAM_MUSIC), any(), any()) }
        verify(exactly = 0) { audioManager.adjustStreamVolume(eq(AudioManager.STREAM_RING), any(), any()) }
    }

    @Test
    fun `volume shows system UI`() {
        controller.volumeUp()
        controller.volumeDown()
        verify(exactly = 2) { audioManager.adjustStreamVolume(any(), any(), eq(AudioManager.FLAG_SHOW_UI)) }
    }

    @Test
    fun `getCurrentVolume returns stream volume`() {
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 7
        assertThat(controller.getCurrentVolume()).isEqualTo(7)
    }

    @Test
    fun `haptic vibrates on volume change when enabled`() {
        controller.hapticEnabled = true
        controller.volumeUp()
        verify { vibrator.vibrate(any<android.os.VibrationEffect>()) }
    }

    @Test
    fun `no vibration when haptic disabled`() {
        controller.hapticEnabled = false
        controller.volumeUp()
        verify(exactly = 0) { vibrator.vibrate(any<android.os.VibrationEffect>()) }
    }

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

    @Test
    fun `vibrateActivation vibrates with pattern`() {
        controller.vibrateActivation()
        verify { vibrator.vibrate(any<android.os.VibrationEffect>()) }
    }
}
