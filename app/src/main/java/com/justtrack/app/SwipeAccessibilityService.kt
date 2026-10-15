package com.justtrack.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager

/**
 * Performs taps, swipes and global actions for CONTROL mode.
 *
 * It subscribes only to window-state changes (see accessibility_service_config.xml),
 * which are used solely to remember which app was in the foreground before the
 * current one for the "previous app" gesture. Window content is never read.
 */
class SwipeAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "SwipeA11yService"

        @Volatile
        var instance: SwipeAccessibilityService? = null
            private set
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val trackableCache = HashMap<String, Boolean>()
    private var homePackage: String? = null
    private var inputMethodPackages: Set<String> = emptySet()
    private val appTracker = ForegroundAppTracker(::isTrackablePackage)

    override fun onServiceConnected() {
        super.onServiceConnected()
        homePackage = resolveHomePackage()
        inputMethodPackages = getSystemService(InputMethodManager::class.java)
            ?.enabledInputMethodList
            ?.map { it.packageName }
            ?.toSet()
            ?: emptySet()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            appTracker.onForegroundPackage(event.packageName?.toString())
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        appTracker.clear()
        super.onDestroy()
    }

    /**
     * Starts a swipe. Returns false if the system rejected it immediately;
     * otherwise [onDone] later reports whether it completed or was cancelled.
     */
    fun performSwipe(
        startX: Float, startY: Float, endX: Float, endY: Float,
        durationMs: Long = 300,
        onDone: ((Boolean) -> Unit)? = null
    ): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        return dispatch(path, durationMs, onDone)
    }

    fun performTap(x: Float, y: Float, onDone: ((Boolean) -> Unit)? = null): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatch(path, 50, onDone)
    }

    private fun dispatch(path: Path, durationMs: Long, onDone: ((Boolean) -> Unit)?): Boolean {
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                onDone?.invoke(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                onDone?.invoke(false)
            }
        }
        val accepted = dispatchGesture(gesture, callback, mainHandler)
        if (!accepted) onDone?.invoke(false)
        return accepted
    }

    fun goBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun recentApps(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    /**
     * Brings the previously used app back to the front. Falls back to the
     * Recents screen when no previous app is known yet (e.g. right after the
     * service was enabled).
     */
    fun switchToPreviousApp(): Boolean {
        val previous = appTracker.previous ?: return recentApps()
        if (previous == homePackage) return goHome()
        val intent = packageManager.getLaunchIntentForPackage(previous) ?: return recentApps()
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            startActivity(intent)
            true
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Could not switch to previous app", e)
            false
        }
    }

    private fun isTrackablePackage(packageName: String): Boolean =
        trackableCache.getOrPut(packageName) {
            when {
                packageName == homePackage -> true
                packageName in inputMethodPackages -> false
                else -> packageManager.getLaunchIntentForPackage(packageName) != null
            }
        }

    private fun resolveHomePackage(): String? {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager.resolveActivity(home, 0)?.activityInfo?.packageName
    }
}
