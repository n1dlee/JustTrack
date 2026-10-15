package com.justtrack.app

import android.os.SystemClock
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
    data object SwipeLeft : GestureAction
    data object SwipeRight : GestureAction
    data object TapScreen : GestureAction
    data object GoBack : GestureAction
    data object GoHome : GestureAction
    data object RecentApps : GestureAction
    data class ScreenScroll(val deltaX: Float, val deltaY: Float) : GestureAction
    data class TapAt(val x: Float, val y: Float) : GestureAction
    data object PreviousApp : GestureAction
    data class CursorMove(val x: Float, val y: Float) : GestureAction
    data object ModeSwitch : GestureAction
}

enum class ControlMode { MEDIA, CONTROL }

/**
 * Turns per-frame hand landmarks into [GestureAction]s.
 *
 * Time comes from [timeProvider], which defaults to a monotonic clock so wall-clock
 * changes cannot stretch or shorten holds and timeouts.
 */
class PinchVolumeGesture(
    private var pinchThreshold: Float = DEFAULT_PINCH_THRESHOLD,
    private var moveThreshold: Float = DEFAULT_MOVE_THRESHOLD,
    private val timeProvider: () -> Long = { SystemClock.elapsedRealtime() }
) {

    private enum class PoseCandidate { NONE, POINT, PEACE, THUMBS_UP, THUMBS_DOWN, PINCH }

    companion object {
        /**
         * Thumb-to-index distance, as a fraction of the palm length (wrist to
         * middle-finger knuckle), below which the fingers count as touching.
         * Relative to the hand so it does not change with distance to the camera.
         */
        const val DEFAULT_PINCH_THRESHOLD = 0.20f
        const val DEFAULT_MOVE_THRESHOLD = 0.04f
        private const val WRIST = 0
        private const val THUMB_TIP = 4
        private const val THUMB_IP = 3
        private const val INDEX_MCP = 5
        private const val INDEX_PIP = 6
        private const val INDEX_TIP = 8
        private const val MIDDLE_MCP = 9
        private const val MIDDLE_PIP = 10
        private const val MIDDLE_TIP = 12
        private const val RING_MCP = 13
        private const val RING_PIP = 14
        private const val RING_TIP = 16
        private const val PINKY_MCP = 17
        private const val PINKY_PIP = 18
        private const val PINKY_TIP = 20
        private const val PALM_CENTER = 9
        const val TAP_MAX_DURATION_MS = 300L
        const val TAP_WAIT_TIMEOUT_MS = 450L
        const val MAX_TAP_COUNT = 3
        const val ACTIVATION_HOLD_MS = 1500L
        const val DEACTIVATION_TIMEOUT_MS = 10_000L
        const val MODE_SWITCH_HOLD_MS = 1500L
        const val THUMB_HOLD_MS = 800L
        const val POSE_MIN_FRAMES = 3
        const val POSE_CONFIRM_MS = 100L
        const val POSE_REARM_FRAMES = 3
        const val CURSOR_RELEASE_FRAMES = 2
        const val HAND_LOST_GRACE_MS = 250L
        /** Cursor is taken from this long before the click, before the finger bent. */
        const val CLICK_LOOKBACK_MS = 120L
        /** A pinch this soon after lowering the pointer clicks at the cursor right away. */
        const val POINTER_CLICK_WINDOW_MS = 500L
        private const val MIN_HAND_SCALE = 0.02f
        private const val PINCH_RELEASE_FACTOR = 1.35f
        private const val SWIPE_THRESHOLD_FACTOR = 2.5f
        private const val OPEN_HAND_SPREAD_THRESHOLD = 0.08f
        private const val OPEN_HAND_SPAN_THRESHOLD = 0.25f
        private const val THUMB_EXTENDED_THRESHOLD = 0.10f
        private const val CURSOR_TIMEOUT_MS = 5000L
        private const val CURSOR_HISTORY_SIZE = 16
        private const val FINGER_MARGIN = 0.04f
        private const val FINGER_EXTENDED_ANGLE_DEG = 35f
        private const val FINGER_FOLDED_ANGLE_DEG = 55f
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

    /** Width / height of the analysed frame; corrects angles for non-square frames. */
    var frameAspect = 1f

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
    private var cursorReleaseFrames = 0
    private val cursorFilterX = OneEuroFilter()
    private val cursorFilterY = OneEuroFilter()
    private val cursorHistoryTime = LongArray(CURSOR_HISTORY_SIZE)
    private val cursorHistoryX = FloatArray(CURSOR_HISTORY_SIZE)
    private val cursorHistoryY = FloatArray(CURSOR_HISTORY_SIZE)
    private var cursorHistoryCount = 0
    private var cursorHistoryNext = 0
    private var pointerExitTime = Long.MIN_VALUE / 2
    private var clickFromPointer = false
    private var poseCandidateType = PoseCandidate.NONE
    private var poseCandidateFrames = 0
    private var poseCandidateStart = 0L
    private var blockedPose = PoseCandidate.NONE
    private var blockedReleaseFrames = 0
    private var lastPalmX = 0f
    private var lastPalmY = 0f
    private var hasLastPalm = false
    private var handLostSince = -1L

    /** True while the pointer (index finger up) is steering the cursor. */
    val isPointing: Boolean get() = state == State.CURSOR_MODE

    /** Whether thumb and index are currently considered touching (with hysteresis). */
    var isPinching = false
        private set

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
        if (mode != controlMode) {
            clearPendingTaps()
            if (state == State.TAP_WAITING) state = State.IDLE
        }
        controlMode = mode
    }

    fun process(landmarks: List<NormalizedLandmark>): GestureAction {
        if (landmarks.size < 21) return onHandLost()

        val now = timeProvider()
        handLostSince = -1L

        if (isActivated && now - lastGestureTime > DEACTIVATION_TIMEOUT_MS) {
            deactivate()
            return GestureAction.None
        }

        val thumbTip = landmarks[THUMB_TIP]
        val indexTip = landmarks[INDEX_TIP]
        val handScale = maxOf(distance(landmarks[WRIST], landmarks[MIDDLE_MCP]), MIN_HAND_SCALE)
        val pinchDistance = distance(thumbTip, indexTip) / handScale
        val midY = (thumbTip.y() + indexTip.y()) / 2f
        val midX = (thumbTip.x() + indexTip.x()) / 2f
        // Enter below the threshold, leave only clearly above it, so jitter around
        // the boundary cannot split one pinch into several.
        val releaseThreshold = pinchThreshold * PINCH_RELEASE_FACTOR
        val fingersClose = pinchDistance < if (isPinching) releaseThreshold else pinchThreshold
        isPinching = fingersClose

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

            State.IDLE -> processIdle(landmarks, now, fingersClose, midX, midY)

            State.PINCHED -> {
                if (!fingersClose) {
                    if (now - pinchStartTime < TAP_MAX_DURATION_MS && clickFromPointer) {
                        // Pointer click: no need to wait for a double/triple tap.
                        clickFromPointer = false
                        lastGestureTime = now
                        return clickAtCursor()
                    }
                    clickFromPointer = false
                    if (now - pinchStartTime < TAP_MAX_DURATION_MS) {
                        tapCount++
                        lastTapTime = now
                        if (tapCount >= MAX_TAP_COUNT) {
                            lastGestureTime = now
                            return resolveTaps()
                        }
                        state = State.TAP_WAITING
                    } else {
                        clearPendingTaps()
                        state = State.IDLE
                    }
                } else if (now - pinchStartTime > TAP_MAX_DURATION_MS) {
                    // A held pinch is a drag, never part of a tap series.
                    clickFromPointer = false
                    clearPendingTaps()
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
                // Timeout first: a pinch that arrives after the window starts a new
                // series instead of being merged into the previous one.
                if (now - lastTapTime > TAP_WAIT_TIMEOUT_MS) {
                    lastGestureTime = now
                    return resolveTaps()
                }
                if (fingersClose) startPinch(now, midX, midY, fromPointer = false)
            }

            State.MODE_SWITCHING -> {
                if (!isPeaceSign(landmarks)) {
                    state = State.IDLE
                } else if (now - modeSwitchStartTime >= MODE_SWITCH_HOLD_MS) {
                    controlMode = when (controlMode) {
                        ControlMode.MEDIA -> ControlMode.CONTROL
                        ControlMode.CONTROL -> ControlMode.MEDIA
                    }
                    clearPendingTaps()
                    blockPoseUntilReleased(PoseCandidate.PEACE)
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
                        blockPoseUntilReleased(if (thumbIsUp) PoseCandidate.THUMBS_UP else PoseCandidate.THUMBS_DOWN)
                        state = State.IDLE
                        return if (thumbIsUp) GestureAction.RecentApps else GestureAction.PreviousApp
                    }
                }
            }

            State.CURSOR_MODE -> {
                lastGestureTime = now
                if (fingersClose) {
                    // Thumb touched the index finger: click where the pointer was a
                    // moment ago, before the finger started bending towards the thumb.
                    freezeCursor(now)
                    startPinch(now, midX, midY, fromPointer = true)
                    return GestureAction.None
                }
                if (!isPointing(landmarks)) {
                    // Require the pose to be gone for a couple of frames so a single
                    // misdetected frame does not drop the cursor mid-move.
                    if (++cursorReleaseFrames >= CURSOR_RELEASE_FRAMES) {
                        freezeCursor(now)
                        pointerExitTime = now
                        state = State.IDLE
                    }
                    return GestureAction.None
                }
                cursorReleaseFrames = 0
                val tip = landmarks[INDEX_TIP]
                cursorX = cursorFilterX.filter(CursorMapping.screenX(tip.x()), now)
                cursorY = cursorFilterY.filter(CursorMapping.screenY(tip.y()), now)
                recordCursor(now)
                return GestureAction.CursorMove(cursorX, cursorY)
            }
        }

        return GestureAction.None
    }

    private fun processIdle(
        landmarks: List<NormalizedLandmark>,
        now: Long,
        fingersClose: Boolean,
        midX: Float,
        midY: Float
    ) {
        if (isOpenHand(landmarks)) {
            lastGestureTime = now
        }
        if (hasCursorPosition && now - cursorSetTime > CURSOR_TIMEOUT_MS) {
            hasCursorPosition = false
        }

        // Pinch right after lowering the pointer: click at the cursor immediately,
        // without waiting for pose confirmation.
        if (fingersClose && hasCursorPosition && now - pointerExitTime <= POINTER_CLICK_WINDOW_MS) {
            pointerExitTime = Long.MIN_VALUE / 2
            hasLastPalm = false
            resetPoseCandidate()
            startPinch(now, midX, midY, fromPointer = true)
            return
        }

        val palm = landmarks[PALM_CENTER]
        val palmMoved = if (hasLastPalm) dist(palm.x(), palm.y(), lastPalmX, lastPalmY) else 0f
        val isStill = !hasLastPalm || palmMoved < POSE_STILLNESS_THRESHOLD
        lastPalmX = palm.x()
        lastPalmY = palm.y()
        hasLastPalm = true

        var candidate = when {
            fingersClose && !allFingersFolded(landmarks) -> PoseCandidate.PINCH
            controlMode == ControlMode.CONTROL && isPointing(landmarks) -> PoseCandidate.POINT
            isPeaceSign(landmarks) -> PoseCandidate.PEACE
            isThumbsUp(landmarks) -> PoseCandidate.THUMBS_UP
            isThumbsDown(landmarks) -> PoseCandidate.THUMBS_DOWN
            else -> PoseCandidate.NONE
        }

        // A one-shot pose (mode switch, thumbs) must be released before it can fire again.
        if (blockedPose != PoseCandidate.NONE) {
            if (candidate == blockedPose) {
                candidate = PoseCandidate.NONE
                blockedReleaseFrames = 0
            } else if (++blockedReleaseFrames >= POSE_REARM_FRAMES) {
                blockedPose = PoseCandidate.NONE
                blockedReleaseFrames = 0
            }
        }

        when {
            candidate == PoseCandidate.NONE || !isStill -> resetPoseCandidate()
            candidate == poseCandidateType -> poseCandidateFrames++
            else -> {
                poseCandidateType = candidate
                poseCandidateFrames = 1
                poseCandidateStart = now
            }
        }

        val confirmed = poseCandidateType
        if (confirmed != PoseCandidate.NONE &&
            poseCandidateFrames >= POSE_MIN_FRAMES &&
            now - poseCandidateStart >= POSE_CONFIRM_MS
        ) {
            resetPoseCandidate()
            hasLastPalm = false
            enterPose(confirmed, now, midX, midY)
        }
    }

    private fun enterPose(pose: PoseCandidate, now: Long, midX: Float, midY: Float) {
        lastGestureTime = now
        when (pose) {
            PoseCandidate.POINT -> {
                state = State.CURSOR_MODE
                cursorReleaseFrames = 0
                cursorHistoryCount = 0
                cursorFilterX.reset()
                cursorFilterY.reset()
            }
            PoseCandidate.PEACE -> {
                state = State.MODE_SWITCHING
                modeSwitchStartTime = now
            }
            PoseCandidate.THUMBS_UP, PoseCandidate.THUMBS_DOWN -> {
                state = State.THUMB_GESTURE
                thumbIsUp = pose == PoseCandidate.THUMBS_UP
                thumbGestureStartTime = now
            }
            PoseCandidate.PINCH -> startPinch(now, midX, midY, fromPointer = false)
            PoseCandidate.NONE -> {}
        }
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
        val thumbExtended = distance(landmarks[THUMB_TIP], landmarks[INDEX_PIP]) > THUMB_EXTENDED_THRESHOLD
        return thumbDown && thumbExtended && allFingersFolded(landmarks)
    }

    /** Index finger extended; middle, ring and pinky folded (pointing up). */
    fun isPointing(landmarks: List<NormalizedLandmark>): Boolean {
        if (landmarks.size < 21) return false
        return isFingerExtended(INDEX_MCP, INDEX_PIP, INDEX_TIP, landmarks) &&
               isFingerFolded(MIDDLE_MCP, MIDDLE_PIP, MIDDLE_TIP, landmarks) &&
               isFingerFolded(RING_MCP, RING_PIP, RING_TIP, landmarks) &&
               isFingerFolded(PINKY_MCP, PINKY_PIP, PINKY_TIP, landmarks)
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
     * x is scaled by [frameAspect] so the angle is measured in pixel space, not in
     * coordinates normalised separately by width and height.
     */
    private fun curlAngleDeg(mcp: NormalizedLandmark, pip: NormalizedLandmark, tip: NormalizedLandmark): Float {
        val v1x = (pip.x() - mcp.x()) * frameAspect
        val v1y = pip.y() - mcp.y()
        val v2x = (tip.x() - pip.x()) * frameAspect
        val v2y = tip.y() - pip.y()
        val mag1 = sqrt(v1x * v1x + v1y * v1y)
        val mag2 = sqrt(v2x * v2x + v2y * v2y)
        if (mag1 < 1e-6f || mag2 < 1e-6f) return 0f
        val cos = ((v1x * v2x + v1y * v2y) / (mag1 * mag2)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cos.toDouble())).toFloat()
    }

    private fun startPinch(now: Long, midX: Float, midY: Float, fromPointer: Boolean) {
        state = State.PINCHED
        pinchStartTime = now
        volumeStartY = midY
        dragStartX = midX
        dragStartY = midY
        lastGestureTime = now
        clickFromPointer = fromPointer
    }

    private fun recordCursor(now: Long) {
        cursorHistoryTime[cursorHistoryNext] = now
        cursorHistoryX[cursorHistoryNext] = cursorX
        cursorHistoryY[cursorHistoryNext] = cursorY
        cursorHistoryNext = (cursorHistoryNext + 1) % CURSOR_HISTORY_SIZE
        if (cursorHistoryCount < CURSOR_HISTORY_SIZE) cursorHistoryCount++
    }

    /**
     * Fixes the cursor at the newest position recorded at least [CLICK_LOOKBACK_MS]
     * ago (or the oldest one available), so the bend of the finger at the end of
     * pointing does not drag the click point.
     */
    private fun freezeCursor(now: Long) {
        if (cursorHistoryCount > 0) {
            val cutoff = now - CLICK_LOOKBACK_MS
            var chosen = -1
            for (i in 0 until cursorHistoryCount) {
                val idx = (cursorHistoryNext - 1 - i + CURSOR_HISTORY_SIZE) % CURSOR_HISTORY_SIZE
                chosen = idx
                if (cursorHistoryTime[idx] <= cutoff) break
            }
            cursorX = cursorHistoryX[chosen]
            cursorY = cursorHistoryY[chosen]
        }
        hasCursorPosition = true
        cursorSetTime = now
    }

    private fun clickAtCursor(): GestureAction {
        clearPendingTaps()
        state = State.IDLE
        if (!hasCursorPosition) return GestureAction.TapScreen
        hasCursorPosition = false
        return GestureAction.TapAt(cursorX, cursorY)
    }

    /**
     * Called when no usable hand is in the frame. Holds that need continuous
     * observation are cancelled so their timer cannot complete across a gap;
     * drags get a short grace period for one- or two-frame dropouts; already
     * finished taps may still resolve.
     */
    private fun onHandLost(): GestureAction {
        val now = timeProvider()
        if (handLostSince < 0) handLostSince = now
        val lostFor = now - handLostSince

        resetPoseCandidate()
        hasLastPalm = false
        isPinching = false
        if (lostFor > HAND_LOST_GRACE_MS) {
            blockedPose = PoseCandidate.NONE
            blockedReleaseFrames = 0
        }

        if (isActivated && now - lastGestureTime > DEACTIVATION_TIMEOUT_MS) {
            deactivate()
            return GestureAction.None
        }

        when (state) {
            State.ACTIVATING -> state = State.INACTIVE
            State.MODE_SWITCHING, State.THUMB_GESTURE -> state = State.IDLE
            State.PINCHED -> {
                clearPendingTaps()
                state = State.IDLE
            }
            State.VOLUME_MODE, State.CURSOR_MODE -> {
                if (lostFor > HAND_LOST_GRACE_MS) state = State.IDLE
            }
            State.TAP_WAITING -> {
                if (now - lastTapTime > TAP_WAIT_TIMEOUT_MS) {
                    lastGestureTime = now
                    return resolveTaps()
                }
            }
            State.INACTIVE, State.IDLE -> {}
        }

        return GestureAction.None
    }

    private fun blockPoseUntilReleased(pose: PoseCandidate) {
        blockedPose = pose
        blockedReleaseFrames = 0
    }

    private fun resetPoseCandidate() {
        poseCandidateType = PoseCandidate.NONE
        poseCandidateFrames = 0
    }

    private fun clearPendingTaps() {
        tapCount = 0
    }

    private fun deactivate() {
        state = State.INACTIVE
        clearPendingTaps()
        hasCursorPosition = false
        resetPoseCandidate()
        blockedPose = PoseCandidate.NONE
        hasLastPalm = false
        isPinching = false
    }

    private fun resolveTaps(): GestureAction {
        if (tapCount <= 0) {
            state = State.IDLE
            return GestureAction.None
        }
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
        clearPendingTaps()
        state = State.IDLE
        return action
    }

    fun reset() {
        deactivate()
        handLostSince = -1L
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
