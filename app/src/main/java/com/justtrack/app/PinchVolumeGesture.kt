package com.justtrack.app

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.abs
import kotlin.math.sqrt

sealed interface GestureAction {
    data object None : GestureAction
    data class VolumeSet(val deltaY: Float) : GestureAction
    data object PlayPause : GestureAction
    data object NextTrack : GestureAction
    data object PrevTrack : GestureAction
    data object SwipeUp : GestureAction
    data object SwipeDown : GestureAction
    data object SwipeLeft : GestureAction
    data object SwipeRight : GestureAction
    data object TapScreen : GestureAction
    data object DoubleTapScreen : GestureAction
    data object ModeSwitch : GestureAction
}

enum class ControlMode { MEDIA, SCREEN }

class PinchVolumeGesture(
    private var pinchThreshold: Float = DEFAULT_PINCH_THRESHOLD,
    private var moveThreshold: Float = DEFAULT_MOVE_THRESHOLD,
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {

    companion object {
        const val DEFAULT_PINCH_THRESHOLD = 0.05f
        const val DEFAULT_MOVE_THRESHOLD = 0.04f
        private const val THUMB_TIP = 4
        private const val INDEX_TIP = 8
        private const val MIDDLE_TIP = 12
        private const val RING_TIP = 16
        private const val PINKY_TIP = 20
        private const val INDEX_PIP = 6
        private const val MIDDLE_PIP = 10
        private const val RING_PIP = 14
        private const val PINKY_PIP = 18
        const val TAP_MAX_DURATION_MS = 300L
        const val TAP_WAIT_TIMEOUT_MS = 450L
        const val ACTIVATION_HOLD_MS = 1500L
        const val DEACTIVATION_TIMEOUT_MS = 10_000L
        const val MODE_SWITCH_HOLD_MS = 1500L
        private const val SWIPE_THRESHOLD_FACTOR = 2.5f
        private const val OPEN_HAND_SPREAD_THRESHOLD = 0.08f
        private const val OPEN_HAND_SPAN_THRESHOLD = 0.25f
    }

    enum class State {
        INACTIVE,
        ACTIVATING,
        IDLE,
        PINCHED,
        VOLUME_MODE,
        TAP_WAITING,
        MODE_SWITCHING
    }

    @Volatile var state = State.INACTIVE
        private set

    var controlMode = ControlMode.MEDIA
        private set

    private var activationStartTime = 0L
    private var lastGestureTime = 0L
    private var pinchStartTime = 0L
    private var volumeStartY = 0f
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var tapCount = 0
    private var lastTapTime = 0L
    private var modeSwitchStartTime = 0L

    val isActivated: Boolean get() = state != State.INACTIVE && state != State.ACTIVATING

    val activationProgress: Float get() {
        if (state != State.ACTIVATING) return 0f
        val elapsed = timeProvider() - activationStartTime
        return (elapsed.toFloat() / ACTIVATION_HOLD_MS).coerceIn(0f, 1f)
    }

    val modeSwitchProgress: Float get() {
        if (state != State.MODE_SWITCHING) return 0f
        val elapsed = timeProvider() - modeSwitchStartTime
        return (elapsed.toFloat() / MODE_SWITCH_HOLD_MS).coerceIn(0f, 1f)
    }

    fun updateThresholds(pinch: Float, move: Float) {
        pinchThreshold = pinch
        moveThreshold = move
    }

    fun setMode(mode: ControlMode) {
        controlMode = mode
    }

    fun process(landmarks: List<NormalizedLandmark>): GestureAction {
        if (landmarks.size < 21) return checkTimeouts()

        val now = timeProvider()

        if (isActivated && now - lastGestureTime > DEACTIVATION_TIMEOUT_MS) {
            deactivate()
            return GestureAction.None
        }

        val thumbTip = landmarks[THUMB_TIP]
        val indexTip = landmarks[INDEX_TIP]
        val distance = distance(thumbTip, indexTip)
        val midY = (thumbTip.y() + indexTip.y()) / 2f
        val midX = (thumbTip.x() + indexTip.x()) / 2f
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
                if (isPeaceSign(landmarks)) {
                    state = State.MODE_SWITCHING
                    modeSwitchStartTime = now
                    lastGestureTime = now
                } else if (fingersClose) {
                    state = State.PINCHED
                    pinchStartTime = now
                    volumeStartY = midY
                    dragStartX = midX
                    dragStartY = midY
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
                    lastGestureTime = now
                }
            }

            State.VOLUME_MODE -> {
                if (!fingersClose) {
                    state = State.IDLE
                    return GestureAction.None
                }
                lastGestureTime = now

                return when (controlMode) {
                    ControlMode.MEDIA -> {
                        val deltaY = volumeStartY - midY
                        GestureAction.VolumeSet(deltaY)
                    }
                    ControlMode.SCREEN -> {
                        val deltaX = midX - dragStartX
                        val deltaY = midY - dragStartY
                        val absDX = abs(deltaX)
                        val absDY = abs(deltaY)
                        val swipeThreshold = moveThreshold * SWIPE_THRESHOLD_FACTOR

                        if (absDX > swipeThreshold && absDX > absDY) {
                            dragStartX = midX
                            dragStartY = midY
                            if (deltaX > 0) GestureAction.SwipeRight else GestureAction.SwipeLeft
                        } else if (absDY > swipeThreshold && absDY > absDX) {
                            dragStartX = midX
                            dragStartY = midY
                            if (deltaY > 0) GestureAction.SwipeDown else GestureAction.SwipeUp
                        } else {
                            GestureAction.None
                        }
                    }
                }
            }

            State.TAP_WAITING -> {
                if (fingersClose) {
                    state = State.PINCHED
                    pinchStartTime = now
                    volumeStartY = midY
                    dragStartX = midX
                    dragStartY = midY
                    lastGestureTime = now
                } else if (now - lastTapTime > TAP_WAIT_TIMEOUT_MS) {
                    lastGestureTime = now
                    return resolveTaps()
                }
            }

            State.MODE_SWITCHING -> {
                if (!isPeaceSign(landmarks)) {
                    state = State.IDLE
                } else if (now - modeSwitchStartTime >= MODE_SWITCH_HOLD_MS) {
                    controlMode = if (controlMode == ControlMode.MEDIA) ControlMode.SCREEN else ControlMode.MEDIA
                    state = State.IDLE
                    lastGestureTime = now
                    return GestureAction.ModeSwitch
                }
            }
        }

        return GestureAction.None
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

    fun isPeaceSign(landmarks: List<NormalizedLandmark>): Boolean {
        if (landmarks.size < 21) return false
        val indexExtended = landmarks[INDEX_TIP].y() < landmarks[INDEX_PIP].y()
        val middleExtended = landmarks[MIDDLE_TIP].y() < landmarks[MIDDLE_PIP].y()
        val ringFolded = landmarks[RING_TIP].y() > landmarks[RING_PIP].y()
        val pinkyFolded = landmarks[PINKY_TIP].y() > landmarks[PINKY_PIP].y()
        return indexExtended && middleExtended && ringFolded && pinkyFolded
    }

    private fun checkTimeouts(): GestureAction {
        val now = timeProvider()

        if (isActivated && now - lastGestureTime > DEACTIVATION_TIMEOUT_MS) {
            deactivate()
            return GestureAction.None
        }

        if (state == State.TAP_WAITING && now - lastTapTime > TAP_WAIT_TIMEOUT_MS) {
            lastGestureTime = now
            return resolveTaps()
        }
        return GestureAction.None
    }

    private fun deactivate() {
        state = State.INACTIVE
        tapCount = 0
    }

    private fun resolveTaps(): GestureAction {
        val action = if (controlMode == ControlMode.MEDIA) {
            when (tapCount) {
                1 -> GestureAction.PlayPause
                2 -> GestureAction.NextTrack
                else -> GestureAction.PrevTrack
            }
        } else {
            when (tapCount) {
                1 -> GestureAction.TapScreen
                else -> GestureAction.DoubleTapScreen
            }
        }
        tapCount = 0
        state = State.IDLE
        return action
    }

    fun reset() {
        state = State.INACTIVE
        tapCount = 0
    }

    private fun distance(a: NormalizedLandmark, b: NormalizedLandmark): Float {
        val dx = a.x() - b.x()
        val dy = a.y() - b.y()
        return sqrt(dx * dx + dy * dy)
    }
}
