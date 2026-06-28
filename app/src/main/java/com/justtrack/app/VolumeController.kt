package com.justtrack.app

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.KeyEvent

class VolumeController(context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    var hapticEnabled = true

    fun volumeUp() {
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            AudioManager.ADJUST_RAISE,
            AudioManager.FLAG_SHOW_UI
        )
        if (hapticEnabled) vibrate()
    }

    fun volumeDown() {
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI
        )
        if (hapticEnabled) vibrate()
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

    fun vibrateActivation() {
        val pattern = longArrayOf(0, 80, 60, 80)
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    private fun vibrate(durationMs: Long = 30) {
        vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}
