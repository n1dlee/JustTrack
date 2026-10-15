package com.justtrack.app

/**
 * The actual state of the tracking pipeline, published by [GestureService].
 * RUNNING means hand-tracking results are arriving, not merely that start was requested.
 */
data class TrackingStatus(val phase: Phase, val error: Error? = null) {

    enum class Phase { STOPPED, STARTING, RUNNING, ERROR }

    enum class Error {
        CAMERA_PERMISSION,
        MODEL_UNAVAILABLE,
        CAMERA_UNAVAILABLE,
        NO_FRAMES,
        FOREGROUND_NOT_ALLOWED
    }

    /** True while the camera pipeline is (or is trying to be) running. */
    val isActive: Boolean
        get() = phase == Phase.STARTING || phase == Phase.RUNNING ||
            (phase == Phase.ERROR && error == Error.NO_FRAMES)

    companion object {
        val STOPPED = TrackingStatus(Phase.STOPPED)
        val STARTING = TrackingStatus(Phase.STARTING)
        val RUNNING = TrackingStatus(Phase.RUNNING)
        fun error(error: Error) = TrackingStatus(Phase.ERROR, error)
    }
}
