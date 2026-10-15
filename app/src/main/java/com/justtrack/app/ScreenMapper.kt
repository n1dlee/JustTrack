package com.justtrack.app

import android.content.Context
import android.graphics.Point
import android.graphics.PointF
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display

/**
 * The single conversion from a normalised cursor position to screen pixels.
 *
 * Both the visible cursor (any overlay, in-app or system-wide) and the
 * Accessibility tap use this, so what is drawn is exactly where the tap lands.
 * Coordinates are in the full physical display space in its current rotation,
 * which is the space [android.accessibilityservice.AccessibilityService.dispatchGesture] uses.
 */
object ScreenMapper {

    fun displaySize(context: Context): Point {
        val display = context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
            ?: return Point(context.resources.displayMetrics.widthPixels, context.resources.displayMetrics.heightPixels)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        return Point(metrics.widthPixels, metrics.heightPixels)
    }

    fun toScreen(context: Context, normalizedX: Float, normalizedY: Float): PointF {
        val size = displaySize(context)
        return toScreen(size.x, size.y, normalizedX, normalizedY)
    }

    fun toScreen(width: Int, height: Int, normalizedX: Float, normalizedY: Float): PointF {
        val x = (normalizedX.coerceIn(0f, 1f) * (width - 1)).coerceAtLeast(0f)
        val y = (normalizedY.coerceIn(0f, 1f) * (height - 1)).coerceAtLeast(0f)
        return PointF(x, y)
    }
}
