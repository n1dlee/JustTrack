package com.justtrack.app

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.KeyEvent

class VolumeController(context: Context) {

    companion object {
        const val VOLUME_SENSITIVITY = 2.5f
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    var hapticEnabled = true
    private var smoothVolumeStart = 0
    private var lastSetVolume = -1

    fun beginSmoothVolume() {
        smoothVolumeStart = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        lastSetVolume = smoothVolumeStart
    }

    fun setVolumeByDelta(deltaY: Float) {
        val maxVol = getMaxVolume()
        val change = (deltaY * VOLUME_SENSITIVITY * maxVol).toInt()
        val newVol = (smoothVolumeStart + change).coerceIn(0, maxVol)
        if (newVol != lastSetVolume) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, AudioManager.FLAG_SHOW_UI)
            lastSetVolume = newVol
            if (hapticEnabled) vibrate(10)
        }
    }

    fun playPause() {
        sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        if (hapticEnabled) vibrate(50)
    }

    fun nextTrack() {
        sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
        if (hapticEnabled) vibrate(40)
    }

    fun prevTrack() {
        sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        if (hapticEnabled) vibrate(40)
    }

    fun getCurrentVolume(): Int =
        audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

    fun getMaxVolume(): Int =
        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    private fun sendMediaKey(keyCode: Int) {
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    fun dispatchAction(action: GestureAction) {
        when (action) {
            is GestureAction.VolumeSet -> setVolumeByDelta(action.deltaY)
            is GestureAction.PlayPause -> playPause()
            is GestureAction.NextTrack -> nextTrack()
            is GestureAction.PrevTrack -> prevTrack()
            else -> {}
        }
    }

    fun vibrateActivation() {
        val pattern = longArrayOf(0, 80, 60, 80)
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    private fun vibrate(durationMs: Long = 30) {
        vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}
