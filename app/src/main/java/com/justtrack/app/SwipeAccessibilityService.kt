package com.justtrack.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

class SwipeAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: SwipeAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun performSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        dispatchGesture(gesture, null, null)
    }

    fun performTap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        dispatchGesture(gesture, null, null)
    }

    fun goBack() = performGlobalAction(GLOBAL_ACTION_BACK)

    fun goHome() = performGlobalAction(GLOBAL_ACTION_HOME)

    fun recentApps() = performGlobalAction(GLOBAL_ACTION_RECENTS)
}
