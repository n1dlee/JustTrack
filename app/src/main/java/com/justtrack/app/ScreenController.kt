package com.justtrack.app

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class ScreenController(context: Context) {

    private val metrics = context.resources.displayMetrics
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    var hapticEnabled = true

    val isAvailable: Boolean get() = SwipeAccessibilityService.instance != null

    private val screenWidth get() = metrics.widthPixels.toFloat()
    private val screenHeight get() = metrics.heightPixels.toFloat()

    fun swipeUp() {
        val service = SwipeAccessibilityService.instance ?: return
        service.performSwipe(screenWidth / 2, screenHeight * 0.7f, screenWidth / 2, screenHeight * 0.3f)
        if (hapticEnabled) vibrate()
    }

    fun swipeDown() {
        val service = SwipeAccessibilityService.instance ?: return
        service.performSwipe(screenWidth / 2, screenHeight * 0.3f, screenWidth / 2, screenHeight * 0.7f)
        if (hapticEnabled) vibrate()
    }

    fun swipeLeft() {
        val service = SwipeAccessibilityService.instance ?: return
        service.performSwipe(screenWidth * 0.7f, screenHeight / 2, screenWidth * 0.3f, screenHeight / 2)
        if (hapticEnabled) vibrate()
    }

    fun swipeRight() {
        val service = SwipeAccessibilityService.instance ?: return
        service.performSwipe(screenWidth * 0.3f, screenHeight / 2, screenWidth * 0.7f, screenHeight / 2)
        if (hapticEnabled) vibrate()
    }

    fun tap() {
        val service = SwipeAccessibilityService.instance ?: return
        service.performTap(screenWidth / 2, screenHeight / 2)
        if (hapticEnabled) vibrate(20)
    }

    fun doubleTap() {
        val service = SwipeAccessibilityService.instance ?: return
        service.performTap(screenWidth / 2, screenHeight / 2)
        mainHandler.postDelayed({
            SwipeAccessibilityService.instance?.performTap(screenWidth / 2, screenHeight / 2)
        }, 150)
        if (hapticEnabled) vibrate(30)
    }

    fun dispatchAction(action: GestureAction) {
        when (action) {
            is GestureAction.SwipeUp -> swipeUp()
            is GestureAction.SwipeDown -> swipeDown()
            is GestureAction.SwipeLeft -> swipeLeft()
            is GestureAction.SwipeRight -> swipeRight()
            is GestureAction.TapScreen -> tap()
            is GestureAction.DoubleTapScreen -> doubleTap()
            else -> {}
        }
    }

    private fun vibrate(durationMs: Long = 30) {
        vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}
