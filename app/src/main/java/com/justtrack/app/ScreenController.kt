package com.justtrack.app

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.abs

/**
 * Turns CONTROL-mode actions into Accessibility gestures and global actions.
 *
 * Nothing is sent while the device is locked, and haptic feedback is given only
 * once the system confirms the action, so a vibration always means "it happened".
 */
class ScreenController(private val context: Context) {

    companion object {
        private const val SCROLL_INTERVAL_MS = 100L
        private const val SCROLL_GESTURE_MS = 80L
        private const val SWIPE_GESTURE_MS = 300L
        private const val SCROLL_SENSITIVITY = 1.0f
        private const val MIN_SCROLL_PX = 5f
        private const val MAX_SCROLL_FACTOR = 0.45f
        private const val EDGE_MARGIN_FACTOR = 0.12f
    }

    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    private val keyguardManager = context.getSystemService(KeyguardManager::class.java)
    var hapticEnabled = true

    private var accScrollX = 0f
    private var accScrollY = 0f
    private var lastScrollTime = 0L
    @Volatile private var scrollInFlight = false

    val isAvailable: Boolean get() = SwipeAccessibilityService.instance != null

    /** The Accessibility service, or null if it is off or the screen is locked. */
    private fun service(): SwipeAccessibilityService? {
        if (keyguardManager?.isKeyguardLocked == true) return null
        return SwipeAccessibilityService.instance
    }

    fun swipeLeft() = horizontalSwipe(fromFraction = 0.7f, toFraction = 0.3f)

    fun swipeRight() = horizontalSwipe(fromFraction = 0.3f, toFraction = 0.7f)

    private fun horizontalSwipe(fromFraction: Float, toFraction: Float) {
        val service = service() ?: return
        val size = ScreenMapper.displaySize(context)
        val y = size.y / 2f
        service.performSwipe(size.x * fromFraction, y, size.x * toFraction, y, SWIPE_GESTURE_MS) { done ->
            if (done) feedback()
        }
    }

    fun tap() = tapAt(0.5f, 0.5f)

    fun tapAt(normalizedX: Float, normalizedY: Float) {
        val service = service() ?: return
        val point = ScreenMapper.toScreen(context, normalizedX, normalizedY)
        service.performTap(point.x, point.y) { done ->
            if (done) feedback(20)
        }
    }

    fun goBack() = globalAction { it.goBack() }

    fun goHome() = globalAction { it.goHome() }

    fun recentApps() = globalAction { it.recentApps() }

    fun previousApp() = globalAction { it.switchToPreviousApp() }

    private inline fun globalAction(action: (SwipeAccessibilityService) -> Boolean) {
        val service = service() ?: return
        if (action(service)) feedback()
    }

    /** Forgets accumulated scroll from a previous drag. */
    fun resetScroll() {
        accScrollX = 0f
        accScrollY = 0f
    }

    /**
     * Accumulates frame-to-frame hand movement and turns it into short swipes.
     * Only one scroll gesture is in flight at a time: a new dispatch would cancel
     * the running one, so movement keeps accumulating until it completes.
     */
    fun scroll(deltaX: Float, deltaY: Float) {
        accScrollX += deltaX
        accScrollY += deltaY

        if (scrollInFlight) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastScrollTime < SCROLL_INTERVAL_MS) return

        val service = service() ?: return
        val size = ScreenMapper.displaySize(context)
        val screenWidth = size.x.toFloat()
        val screenHeight = size.y.toFloat()

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

        scrollInFlight = true
        val accepted = service.performSwipe(startX, startY, endX, endY, SCROLL_GESTURE_MS) {
            scrollInFlight = false
        }
        if (accepted) {
            resetScroll()
            lastScrollTime = now
        }
    }

    fun dispatchAction(action: GestureAction) {
        when (action) {
            is GestureAction.SwipeLeft -> swipeLeft()
            is GestureAction.SwipeRight -> swipeRight()
            is GestureAction.TapScreen -> tap()
            is GestureAction.GoBack -> goBack()
            is GestureAction.GoHome -> goHome()
            is GestureAction.RecentApps -> recentApps()
            is GestureAction.ScreenScroll -> scroll(action.deltaX, action.deltaY)
            is GestureAction.TapAt -> tapAt(action.x, action.y)
            is GestureAction.PreviousApp -> previousApp()
            else -> {}
        }
    }

    private fun feedback(durationMs: Long = 30) {
        if (!hapticEnabled) return
        vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}
