package com.justtrack.app

object PrefsKeys {
    const val PINCH_SENSITIVITY = "pref_pinch_sensitivity"
    const val MOVE_SENSITIVITY = "pref_move_sensitivity"
    const val HAPTIC_FEEDBACK = "pref_haptic_feedback"
    const val MAIN_HAND = "pref_main_hand"
    const val AUTO_START = "pref_auto_start"
    const val CONTROL_MODE = "control_mode"

    const val DEFAULT_SENSITIVITY = 5
    const val DEFAULT_HAPTIC = true
    const val DEFAULT_MAIN_HAND = "both"
    const val DEFAULT_AUTO_START = false

    /** Thumb-index distance as a fraction of palm length; 5 -> 0.20 (fingertips touching). */
    fun pinchThreshold(sensitivity: Int): Float = 0.10f + sensitivity * 0.02f
    fun moveThreshold(sensitivity: Int): Float = 0.09f - sensitivity * 0.008f
}
