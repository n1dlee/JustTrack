package com.justtrack.app

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.telecom.TelecomManager
import kotlin.math.abs

class ScreenController(private val context: Context) {

    companion object {
        private const val SCROLL_INTERVAL_MS = 100L
        private const val SCROLL_GESTURE_MS = 80L
        private const val SCROLL_SENSITIVITY = 1.0f
        private const val MIN_SCROLL_PX = 5f
        private const val MAX_SCROLL_FACTOR = 0.45f
        private const val EDGE_MARGIN_FACTOR = 0.12f
    }

    private val metrics = context.resources.displayMetrics
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    var hapticEnabled = true

    private var accScrollX = 0f
    private var accScrollY = 0f
    private var lastScrollTime = 0L

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

    fun goBack() {
        SwipeAccessibilityService.instance?.goBack()
        if (hapticEnabled) vibrate()
    }

    fun goHome() {
        SwipeAccessibilityService.instance?.goHome()
        if (hapticEnabled) vibrate()
    }

    fun recentApps() {
        SwipeAccessibilityService.instance?.recentApps()
        if (hapticEnabled) vibrate()
    }

    @Suppress("DEPRECATION")
    fun acceptCall() {
        try {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            telecom?.acceptRingingCall()
            if (hapticEnabled) vibrate()
        } catch (_: SecurityException) {}
    }

    @Suppress("DEPRECATION")
    fun declineCall() {
        try {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            telecom?.endCall()
            if (hapticEnabled) vibrate()
        } catch (_: SecurityException) {}
    }

    fun scroll(deltaX: Float, deltaY: Float) {
        accScrollX += deltaX
        accScrollY += deltaY

        val now = SystemClock.uptimeMillis()
        if (now - lastScrollTime < SCROLL_INTERVAL_MS) return

        var pixelX = accScrollX * screenWidth * SCROLL_SENSITIVITY
        var pixelY = accScrollY * screenHeight * SCROLL_SENSITIVITY

        if (abs(pixelX) < MIN_SCROLL_PX && abs(pixelY) < MIN_SCROLL_PX) return

        val maxX = screenWidth * MAX_SCROLL_FACTOR
        val maxY = screenHeight * MAX_SCROLL_FACTOR
        pixelX = pixelX.coerceIn(-maxX, maxX)
        pixelY = pixelY.coerceIn(-maxY, maxY)

        val startX = screenWidth / 2f
        val startY = screenHeight / 2f
        val marginX = screenWidth * EDGE_MARGIN_FACTOR
        val marginY = screenHeight * EDGE_MARGIN_FACTOR
        val endX = (startX + pixelX).coerceIn(marginX, screenWidth - marginX)
        val endY = (startY + pixelY).coerceIn(marginY, screenHeight - marginY)

        SwipeAccessibilityService.instance?.performSwipe(startX, startY, endX, endY, SCROLL_GESTURE_MS)

        accScrollX = 0f
        accScrollY = 0f
        lastScrollTime = now
    }

    fun tapAt(normalizedX: Float, normalizedY: Float) {
        val service = SwipeAccessibilityService.instance ?: return
        val x = (normalizedX * screenWidth).coerceIn(10f, screenWidth - 10f)
        val y = (normalizedY * screenHeight).coerceIn(10f, screenHeight - 10f)
        service.performTap(x, y)
        if (hapticEnabled) vibrate(20)
    }

    fun previousApp() {
        val service = SwipeAccessibilityService.instance ?: return
        service.recentApps()
        mainHandler.postDelayed({
            SwipeAccessibilityService.instance?.recentApps()
        }, 200)
        if (hapticEnabled) vibrate()
    }

    fun dispatchAction(action: GestureAction) {
        when (action) {
            is GestureAction.SwipeUp -> swipeUp()
            is GestureAction.SwipeDown -> swipeDown()
            is GestureAction.SwipeLeft -> swipeLeft()
            is GestureAction.SwipeRight -> swipeRight()
            is GestureAction.TapScreen -> tap()
            is GestureAction.DoubleTapScreen -> doubleTap()
            is GestureAction.GoBack -> goBack()
            is GestureAction.GoHome -> goHome()
            is GestureAction.RecentApps -> recentApps()
            is GestureAction.AcceptCall -> acceptCall()
            is GestureAction.DeclineCall -> declineCall()
            is GestureAction.ScreenScroll -> scroll(action.deltaX, action.deltaY)
            is GestureAction.TapAt -> tapAt(action.x, action.y)
            is GestureAction.PreviousApp -> previousApp()
            else -> {}
        }
    }

    private fun vibrate(durationMs: Long = 30) {
        vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}
