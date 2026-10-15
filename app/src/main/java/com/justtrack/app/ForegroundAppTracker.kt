package com.justtrack.app

/**
 * Remembers the current and previous foreground app so "previous app" can switch
 * back to a specific, known package instead of guessing through the Recents screen.
 *
 * Package names are kept in memory only and never logged or persisted.
 */
class ForegroundAppTracker(private val isTrackable: (String) -> Boolean) {

    var current: String? = null
        private set
    var previous: String? = null
        private set

    fun onForegroundPackage(packageName: String?) {
        if (packageName.isNullOrEmpty() || packageName == current) return
        if (!isTrackable(packageName)) return
        previous = current
        current = packageName
    }

    fun clear() {
        current = null
        previous = null
    }
}
