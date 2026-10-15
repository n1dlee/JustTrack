package com.justtrack.app

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.abs
import kotlin.math.acos
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
    data object GoBack : GestureAction
    data object GoHome : GestureAction
    data object RecentApps : GestureAction
    data object AcceptCall : GestureAction
    data object DeclineCall : GestureAction
    data class ScreenScroll(val deltaX: Float, val deltaY: Float) : GestureAction
    data class TapAt(val x: Float, val y: Float) : GestureAction
    data object PreviousApp : GestureAction
    data class CursorMove(val x: Float, val y: Float) : GestureAction
    data object ModeSwitch : GestureAction
}

enum class ControlMode { MEDIA, CONTROL }

class PinchVolumeGesture(
    private var pinchThreshold: Float = DEFAULT_PINCH_THRESHOLD,
    private var moveThreshold: Float = DEFAULT_MOVE_THRESHOLD,
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {

    private enum class PoseCandidate { NONE, FIST, PEACE, THUMBS_UP, THUMBS_DOWN, PINCH }

    companion object {
        const val DEFAULT_PINCH_THRESHOLD = 0.05f
        const val DEFAULT_MOVE_THRESHOLD = 0.04f
        private const val THUMB_TIP = 4
        private const val THUMB_IP = 3
        private const val INDEX_MCP = 5
        private const val INDEX_TIP = 8
        private const val MIDDLE_MCP = 9
        private const val MIDDLE_TIP = 12
        private const val RING_MCP = 13
        private const val RING_TIP = 16
        private const val PINKY_MCP = 17
        private const val PINKY_TIP = 20
        private const val INDEX_PIP = 6
        private const val MIDDLE_PIP = 10
        private const val RING_PIP = 14
        private const val PINKY_PIP = 18
        private const val PALM_CENTER = 9
        const val TAP_MAX_DURATION_MS = 300L
        const val TAP_WAIT_TIMEOUT_MS = 450L
        const val ACTIVATION_HOLD_MS = 1500L
        const val DEACTIVATION_TIMEOUT_MS = 10_000L
        const val MODE_SWITCH_HOLD_MS = 1500L
        const val THUMB_HOLD_MS = 800L
        private const val SWIPE_THRESHOLD_FACTOR = 2.5f
        private const val OPEN_HAND_SPREAD_THRESHOLD = 0.08f
        private const val OPEN_HAND_SPAN_THRESHOLD = 0.25f
        private const val FIST_THUMB_THRESHOLD = 0.10f
        private const val CURSOR_TIMEOUT_MS = 5000L
        private const val FINGER_MARGIN = 0.04f
        private const val FINGER_EXTENDED_ANGLE_DEG = 35f
        private const val FINGER_FOLDED_ANGLE_DEG = 55f
        const val POSE_CONFIRM_FRAMES = 3
        private const val POSE_STILLNESS_THRESHOLD = 0.035f
    }

    enum class State {
        INACTIVE,
        ACTIVATING,
        IDLE,
        PINCHED,
        VOLUME_MODE,
        TAP_WAITING,
        MODE_SWITCHING,
        THUMB_GESTURE,
        CURSOR_MODE
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
    private var swipeOriginX = 0f
    private var tapCount = 0
    private var lastTapTime = 0L
    private var modeSwitchStartTime = 0L
    private var thumbIsUp = true
    private var thumbGestureStartTime = 0L
    var cursorX = 0.5f
        private set
    var cursorY = 0.5f
        private set
    var hasCursorPosition = false
        private set
    private var cursorSetTime = 0L
    private var poseCandidateType = PoseCandidate.NONE
    private var poseCandidateFrames = 0
    private var lastPalmX = 0f
    private var lastPalmY = 0f
    private var hasLastPalm = false

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

    val thumbGestureProgress: Float get() {
        if (state != State.THUMB_GESTURE) return 0f
        val elapsed = timeProvider() - thumbGestureStartTime
        return (elapsed.toFloat() / THUMB_HOLD_MS).coerceIn(0f, 1f)
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
                if (hasCursorPosition && now - cursorSetTime > CURSOR_TIMEOUT_MS) {
                    hasCursorPosition = false
                }

                val palm = landmarks[PALM_CENTER]
                val palmMoved = if (hasLastPalm) dist(palm.x(), palm.y(), lastPalmX, lastPalmY) else 0f
                val isStill = !hasLastPalm || palmMoved < POSE_STILLNESS_THRESHOLD
                lastPalmX = palm.x()
                lastPalmY = palm.y()
                hasLastPalm = true

                val candidate = when {
                    controlMode == ControlMode.CONTROL && isFist(landmarks) -> PoseCandidate.FIST
                    isPeaceSign(landmarks) -> PoseCandidate.PEACE
                    isThumbsUp(landmarks) -> PoseCandidate.THUMBS_UP
                    isThumbsDown(landmarks) -> PoseCandidate.THUMBS_DOWN
                    fingersClose && !allFingersFolded(landmarks) -> PoseCandidate.PINCH
                    else -> PoseCandidate.NONE
                }

                val sameAsBefore = candidate != PoseCandidate.NONE && candidate == poseCandidateType
                poseCandidateFrames = when {
                    candidate == PoseCandidate.NONE || !isStill -> 0
                    sameAsBefore -> poseCandidateFrames + 1
                    else -> 1
                }
                poseCandidateType = candidate

                if (poseCandidateFrames >= POSE_CONFIRM_FRAMES) {
                    poseCandidateFrames = 0
                    when (candidate) {
                        PoseCandidate.FIST -> {
                            state = State.CURSOR_MODE
                            lastGestureTime = now
                        }
                        PoseCandidate.PEACE -> {
                            state = State.MODE_SWITCHING
                            modeSwitchStartTime = now
                            lastGestureTime = now
                        }
                        PoseCandidate.THUMBS_UP -> {
                            state = State.THUMB_GESTURE
                            thumbIsUp = true
                            thumbGestureStartTime = now
                            lastGestureTime = now
                        }
                        PoseCandidate.THUMBS_DOWN -> {
                            state = State.THUMB_GESTURE
                            thumbIsUp = false
                            thumbGestureStartTime = now
                            lastGestureTime = now
                        }
                        PoseCandidate.PINCH -> {
                            state = State.PINCHED
                            pinchStartTime = now
                            volumeStartY = midY
                            dragStartX = midX
                            dragStartY = midY
                            lastGestureTime = now
                        }
                        PoseCandidate.NONE -> {}
                    }
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
                    swipeOriginX = midX
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
                    ControlMode.CONTROL -> {
                        val cumDeltaX = midX - swipeOriginX
                        val swipeThreshold = moveThreshold * SWIPE_THRESHOLD_FACTOR

                        if (abs(cumDeltaX) > swipeThreshold) {
                            state = State.IDLE
                            if (cumDeltaX > 0) GestureAction.SwipeLeft else GestureAction.SwipeRight
                        } else {
                            val frameDeltaX = dragStartX - midX
                            val frameDeltaY = midY - dragStartY
                            dragStartX = midX
                            dragStartY = midY
                            GestureAction.ScreenScroll(frameDeltaX, frameDeltaY)
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
                    controlMode = when (controlMode) {
                        ControlMode.MEDIA -> ControlMode.CONTROL
                        ControlMode.CONTROL -> ControlMode.MEDIA
                    }
                    state = State.IDLE
                    lastGestureTime = now
                    return GestureAction.ModeSwitch
                }
            }

            State.THUMB_GESTURE -> {
                val stillValid = if (thumbIsUp) isThumbsUp(landmarks) else isThumbsDown(landmarks)
                if (!stillValid) {
                    state = State.IDLE
                } else {
                    lastGestureTime = now
                    if (now - thumbGestureStartTime >= THUMB_HOLD_MS) {
                        state = State.IDLE
                        return if (thumbIsUp) GestureAction.RecentApps else GestureAction.PreviousApp
                    }
                }
            }

            State.CURSOR_MODE -> {
                lastGestureTime = now
                if (!isFist(landmarks)) {
                    hasCursorPosition = true
                    cursorSetTime = now
                    state = State.IDLE
                    return GestureAction.None
                }
                val palm = landmarks[PALM_CENTER]
                cursorX = 1f - palm.x()
                cursorY = palm.y()
                return GestureAction.CursorMove(cursorX, cursorY)
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
        val indexExtended = isFingerExtended(INDEX_MCP, INDEX_PIP, INDEX_TIP, landmarks)
        val middleExtended = isFingerExtended(MIDDLE_MCP, MIDDLE_PIP, MIDDLE_TIP, landmarks)
        val ringFolded = isFingerFolded(RING_MCP, RING_PIP, RING_TIP, landmarks)
        val pinkyFolded = isFingerFolded(PINKY_MCP, PINKY_PIP, PINKY_TIP, landmarks)
        return indexExtended && middleExtended && ringFolded && pinkyFolded
    }

    fun isThumbsUp(landmarks: List<NormalizedLandmark>): Boolean {
        if (landmarks.size < 21) return false
        val thumbUp = landmarks[THUMB_TIP].y() < landmarks[THUMB_IP].y() - FINGER_MARGIN
        return thumbUp && allFingersFolded(landmarks)
    }

    fun isThumbsDown(landmarks: List<NormalizedLandmark>): Boolean {
        if (landmarks.size < 21) return false
        val thumbDown = landmarks[THUMB_TIP].y() > landmarks[THUMB_IP].y() + FINGER_MARGIN
        val thumbExtended = distance(landmarks[THUMB_TIP], landmarks[INDEX_PIP]) > FIST_THUMB_THRESHOLD
        return thumbDown && thumbExtended && allFingersFolded(landmarks)
    }

    fun isFist(landmarks: List<NormalizedLandmark>): Boolean {
        if (landmarks.size < 21) return false
        val thumbTucked = distance(landmarks[THUMB_TIP], landmarks[INDEX_PIP]) < FIST_THUMB_THRESHOLD
        return thumbTucked && allFingersFolded(landmarks)
    }

    private fun allFingersFolded(landmarks: List<NormalizedLandmark>): Boolean {
        return isFingerFolded(INDEX_MCP, INDEX_PIP, INDEX_TIP, landmarks) &&
               isFingerFolded(MIDDLE_MCP, MIDDLE_PIP, MIDDLE_TIP, landmarks) &&
               isFingerFolded(RING_MCP, RING_PIP, RING_TIP, landmarks) &&
               isFingerFolded(PINKY_MCP, PINKY_PIP, PINKY_TIP, landmarks)
    }

    private fun isFingerExtended(mcpIdx: Int, pipIdx: Int, tipIdx: Int, landmarks: List<NormalizedLandmark>): Boolean =
        curlAngleDeg(landmarks[mcpIdx], landmarks[pipIdx], landmarks[tipIdx]) < FINGER_EXTENDED_ANGLE_DEG

    private fun isFingerFolded(mcpIdx: Int, pipIdx: Int, tipIdx: Int, landmarks: List<NormalizedLandmark>): Boolean =
        curlAngleDeg(landmarks[mcpIdx], landmarks[pipIdx], landmarks[tipIdx]) > FINGER_FOLDED_ANGLE_DEG

    /**
     * Angle between the MCP->PIP and PIP->TIP segments. Near 0 for a straight
     * (extended) finger, near 180 for a fully curled one. Unlike a raw y-coordinate
     * comparison this stays meaningful regardless of how the hand is rotated in frame.
     */
    private fun curlAngleDeg(mcp: NormalizedLandmark, pip: NormalizedLandmark, tip: NormalizedLandmark): Float {
        val v1x = pip.x() - mcp.x()
        val v1y = pip.y() - mcp.y()
        val v2x = tip.x() - pip.x()
        val v2y = tip.y() - pip.y()
        val mag1 = sqrt(v1x * v1x + v1y * v1y)
        val mag2 = sqrt(v2x * v2x + v2y * v2y)
        if (mag1 < 1e-6f || mag2 < 1e-6f) return 0f
        val cos = ((v1x * v2x + v1y * v2y) / (mag1 * mag2)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cos.toDouble())).toFloat()
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

        if (state == State.THUMB_GESTURE || state == State.CURSOR_MODE) {
            state = State.IDLE
        }

        poseCandidateType = PoseCandidate.NONE
        poseCandidateFrames = 0
        hasLastPalm = false

        return GestureAction.None
    }

    private fun deactivate() {
        state = State.INACTIVE
        tapCount = 0
        hasCursorPosition = false
        poseCandidateType = PoseCandidate.NONE
        poseCandidateFrames = 0
        hasLastPalm = false
    }

    private fun resolveTaps(): GestureAction {
        val action = when (controlMode) {
            ControlMode.MEDIA -> when (tapCount) {
                1 -> GestureAction.PlayPause
                2 -> GestureAction.NextTrack
                else -> GestureAction.PrevTrack
            }
            ControlMode.CONTROL -> when (tapCount) {
                1 -> if (hasCursorPosition) {
                    val a = GestureAction.TapAt(cursorX, cursorY)
                    hasCursorPosition = false
                    a
                } else {
                    GestureAction.TapScreen
                }
                2 -> GestureAction.GoBack
                else -> GestureAction.GoHome
            }
        }
        tapCount = 0
        state = State.IDLE
        return action
    }

    fun reset() {
        state = State.INACTIVE
        tapCount = 0
        hasCursorPosition = false
        poseCandidateType = PoseCandidate.NONE
        poseCandidateFrames = 0
        hasLastPalm = false
    }

    private fun distance(a: NormalizedLandmark, b: NormalizedLandmark): Float {
        val dx = a.x() - b.x()
        val dy = a.y() - b.y()
        return sqrt(dx * dx + dy * dy)
    }

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return sqrt(dx * dx + dy * dy)
    }
}
