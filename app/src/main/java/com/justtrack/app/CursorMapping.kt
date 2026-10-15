package com.justtrack.app

/**
 * Maps a camera landmark to a normalised screen position.
 *
 * The front-camera frame is mirrored horizontally so moving the hand right moves
 * the cursor right, and the reachable middle of the frame is stretched over the
 * whole screen. The pointer gesture and the system-wide hand overlay both use this,
 * so the cursor circle sits exactly on the drawn fingertip.
 */
object CursorMapping {

    const val INPUT_MARGIN = 0.1f

    fun screenX(landmarkX: Float, clamp: Boolean = true): Float = remap(1f - landmarkX, clamp)

    fun screenY(landmarkY: Float, clamp: Boolean = true): Float = remap(landmarkY, clamp)

    private fun remap(value: Float, clamp: Boolean): Float {
        val mapped = (value - INPUT_MARGIN) / (1f - 2f * INPUT_MARGIN)
        return if (clamp) mapped.coerceIn(0f, 1f) else mapped
    }
}
