package com.justtrack.app

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.abs
import kotlin.math.sqrt

enum class GestureAction {
    VOLUME_UP,
    VOLUME_DOWN,
    PLAY_PAUSE,
    NEXT_TRACK,
    PREV_TRACK,
    NONE
}

class PinchVolumeGesture(
    private var pinchThreshold: Float = DEFAULT_PINCH_THRESHOLD,
    private var moveThreshold: Float = DEFAULT_MOVE_THRESHOLD,
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {

    companion object {
        const val DEFAULT_PINCH_THRESHOLD = 0.05f
        const val DEFAULT_MOVE_THRESHOLD = 0.04f
        private const val COOLDOWN_FRAMES = 1
        private const val THUMB_TIP = 4
        private const val INDEX_TIP = 8
        private const val MIDDLE_TIP = 12
        private const val RING_TIP = 16
        private const val PINKY_TIP = 20
        const val TAP_MAX_DURATION_MS = 300L
        const val TAP_WAIT_TIMEOUT_MS = 450L
        const val ACTIVATION_HOLD_MS = 1500L
        const val DEACTIVATION_TIMEOUT_MS = 10_000L
        private const val OPEN_HAND_SPREAD_THRESHOLD = 0.08f
        private const val OPEN_HAND_SPAN_THRESHOLD = 0.25f
    }

    enum class State {
        INACTIVE,
        ACTIVATING,
        IDLE,
        PINCHED,
        VOLUME_MODE,
        TAP_WAITING
    }

    var state = State.INACTIVE
        private set

    private var activationStartTime = 0L
    private var lastGestureTime = 0L
    private var pinchStartTime = 0L
    private var pinchStartY = 0f
    private var lastActionY = 0f
    private var cooldownCounter = 0
    private var tapCount = 0
    private var lastTapTime = 0L

    val isActivated: Boolean get() = state != State.INACTIVE && state != State.ACTIVATING

    val activationProgress: Float get() {
        if (state != State.ACTIVATING) return 0f
        val elapsed = timeProvider() - activationStartTime
        return (elapsed.toFloat() / ACTIVATION_HOLD_MS).coerceIn(0f, 1f)
    }

    fun updateThresholds(pinch: Float, move: Float) {
        pinchThreshold = pinch
        moveThreshold = move
    }

    fun process(landmarks: List<NormalizedLandmark>): GestureAction {
        if (landmarks.size < 21) return checkTimeouts()

        val now = timeProvider()

        if (isActivated && now - lastGestureTime > DEACTIVATION_TIMEOUT_MS) {
            deactivate()
            return GestureAction.NONE
        }

        val thumbTip = landmarks[THUMB_TIP]
        val indexTip = landmarks[INDEX_TIP]
        val distance = distance(thumbTip, indexTip)
        val midY = (thumbTip.y() + indexTip.y()) / 2f
        val fingersClose = distance < pinchThreshold

        when (state) {
            State.INACTIVE -> {
                if (isOpenHand(landmarks)) {
                    state = State.ACTIVATING
                    activationStartTime = now
                }
            }

            State.ACTIVATING -> {
                if (!isOpenHand(landmarks)) {
                    state = State.INACTIVE
                } else if (now - activationStartTime >= ACTIVATION_HOLD_MS) {
                    state = State.IDLE
                    lastGestureTime = now
                }
            }

            State.IDLE -> {
                if (isOpenHand(landmarks)) {
                    lastGestureTime = now
                }
                if (fingersClose) {
                    state = State.PINCHED
                    pinchStartTime = now
                    pinchStartY = midY
                    lastActionY = midY
                    lastGestureTime = now
                }
            }

            State.PINCHED -> {
                if (!fingersClose) {
                    val duration = now - pinchStartTime
                    if (duration < TAP_MAX_DURATION_MS) {
                        tapCount++
                        lastTapTime = now
                        state = State.TAP_WAITING
                    } else {
                        state = State.IDLE
                    }
                } else if (now - pinchStartTime > TAP_MAX_DURATION_MS) {
                    state = State.VOLUME_MODE
                    cooldownCounter = 0
                    lastGestureTime = now
                }
            }

            State.VOLUME_MODE -> {
                if (!fingersClose) {
                    state = State.IDLE
                    return GestureAction.NONE
                }
                if (cooldownCounter > 0) {
                    cooldownCounter--
                    return GestureAction.NONE
                }
                val deltaY = midY - lastActionY
                if (abs(deltaY) > moveThreshold) {
                    lastActionY = midY
                    cooldownCounter = COOLDOWN_FRAMES
                    lastGestureTime = now
                    return if (deltaY < 0) GestureAction.VOLUME_UP else GestureAction.VOLUME_DOWN
                }
            }

            State.TAP_WAITING -> {
                if (fingersClose) {
                    state = State.PINCHED
                    pinchStartTime = now
                    pinchStartY = midY
                    lastActionY = midY
                    lastGestureTime = now
                } else if (now - lastTapTime > TAP_WAIT_TIMEOUT_MS) {
                    lastGestureTime = now
                    return resolveTaps()
                }
            }
        }

        return GestureAction.NONE
    }

    fun isOpenHand(landmarks: List<NormalizedLandmark>): Boolean {
        if (landmarks.size < 21) return false
        val tips = listOf(
            landmarks[THUMB_TIP],
            landmarks[INDEX_TIP],
            landmarks[MIDDLE_TIP],
            landmarks[RING_TIP],
            landmarks[PINKY_TIP]
        )
        for (i in 0 until tips.size - 1) {
            if (distance(tips[i], tips[i + 1]) < OPEN_HAND_SPREAD_THRESHOLD) return false
        }
        return distance(tips[0], tips[4]) > OPEN_HAND_SPAN_THRESHOLD
    }

    private fun checkTimeouts(): GestureAction {
        val now = timeProvider()

        if (isActivated && now - lastGestureTime > DEACTIVATION_TIMEOUT_MS) {
            deactivate()
            return GestureAction.NONE
        }

        if (state == State.TAP_WAITING && now - lastTapTime > TAP_WAIT_TIMEOUT_MS) {
            lastGestureTime = now
            return resolveTaps()
        }
        return GestureAction.NONE
    }

    private fun deactivate() {
        state = State.INACTIVE
        tapCount = 0
        cooldownCounter = 0
    }

    private fun resolveTaps(): GestureAction {
        val action = when (tapCount) {
            1 -> GestureAction.PLAY_PAUSE
            2 -> GestureAction.NEXT_TRACK
            else -> GestureAction.PREV_TRACK
        }
        tapCount = 0
        state = State.IDLE
        return action
    }

    fun reset() {
        state = State.INACTIVE
        tapCount = 0
        cooldownCounter = 0
    }

    private fun distance(a: NormalizedLandmark, b: NormalizedLandmark): Float {
        val dx = a.x() - b.x()
        val dy = a.y() - b.y()
        return sqrt(dx * dx + dy * dy)
    }
}
