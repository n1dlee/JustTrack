package com.justtrack.app

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

class PinchVolumeGestureTest {

    private lateinit var gesture: PinchVolumeGesture
    private var fakeTime = 0L

    @Before
    fun setUp() {
        fakeTime = 0L
        gesture = PinchVolumeGesture(timeProvider = { fakeTime })
    }

    private fun makeLandmarks(
        thumbX: Float, thumbY: Float,
        indexX: Float, indexY: Float
    ): List<NormalizedLandmark> {
        return List(21) { i ->
            when (i) {
                4 -> NormalizedLandmark.create(thumbX, thumbY, 0f)
                8 -> NormalizedLandmark.create(indexX, indexY, 0f)
                else -> NormalizedLandmark.create(0.5f, 0.5f, 0f)
            }
        }
    }

    private fun makeOpenHandLandmarks(): List<NormalizedLandmark> {
        return List(21) { i ->
            when (i) {
                4 -> NormalizedLandmark.create(0.15f, 0.4f, 0f)
                8 -> NormalizedLandmark.create(0.30f, 0.2f, 0f)
                12 -> NormalizedLandmark.create(0.50f, 0.15f, 0f)
                16 -> NormalizedLandmark.create(0.65f, 0.2f, 0f)
                20 -> NormalizedLandmark.create(0.80f, 0.35f, 0f)
                else -> NormalizedLandmark.create(0.5f, 0.5f, 0f)
            }
        }
    }

    /** mcp->pip->tip roughly colinear pointing away from mcp = a clearly extended finger. */
    private fun extendedFinger(x: Float, mcpY: Float): Triple<Pair<Float, Float>, Pair<Float, Float>, Pair<Float, Float>> {
        val mcp = x to mcpY
        val pip = x to (mcpY - 0.08f)
        val tip = x to (mcpY - 0.20f)
        return Triple(mcp, pip, tip)
    }

    /** tip curls back past pip toward mcp = a clearly folded finger (large curl angle). */
    private fun foldedFinger(x: Float, mcpY: Float): Triple<Pair<Float, Float>, Pair<Float, Float>, Pair<Float, Float>> {
        val mcp = x to mcpY
        val pip = x to (mcpY - 0.06f)
        val tip = x to (mcpY + 0.02f)
        return Triple(mcp, pip, tip)
    }

    private fun setFinger(
        lm: MutableList<NormalizedLandmark>,
        mcpIdx: Int, pipIdx: Int, tipIdx: Int,
        x: Float, mcpY: Float, extended: Boolean
    ) {
        val (mcp, pip, tip) = if (extended) extendedFinger(x, mcpY) else foldedFinger(x, mcpY)
        lm[mcpIdx] = NormalizedLandmark.create(mcp.first, mcp.second, 0f)
        lm[pipIdx] = NormalizedLandmark.create(pip.first, pip.second, 0f)
        lm[tipIdx] = NormalizedLandmark.create(tip.first, tip.second, 0f)
    }

    private fun makePeaceSignLandmarks(): List<NormalizedLandmark> {
        val lm = MutableList(21) { NormalizedLandmark.create(0.5f, 0.5f, 0f) }
        setFinger(lm, 5, 6, 8, 0.40f, 0.5f, extended = true)    // index
        setFinger(lm, 9, 10, 12, 0.50f, 0.5f, extended = true)  // middle
        setFinger(lm, 13, 14, 16, 0.60f, 0.5f, extended = false) // ring
        setFinger(lm, 17, 18, 20, 0.70f, 0.5f, extended = false) // pinky
        return lm
    }

    private fun makeThumbsUpLandmarks(): List<NormalizedLandmark> {
        val lm = MutableList(21) { NormalizedLandmark.create(0.5f, 0.5f, 0f) }
        lm[3] = NormalizedLandmark.create(0.45f, 0.35f, 0f)  // thumb IP
        lm[4] = NormalizedLandmark.create(0.45f, 0.20f, 0f)  // thumb TIP (above IP = pointing up)
        setFinger(lm, 5, 6, 8, 0.45f, 0.5f, extended = false)
        setFinger(lm, 9, 10, 12, 0.50f, 0.5f, extended = false)
        setFinger(lm, 13, 14, 16, 0.60f, 0.5f, extended = false)
        setFinger(lm, 17, 18, 20, 0.65f, 0.5f, extended = false)
        return lm
    }

    private fun makeThumbsDownLandmarks(): List<NormalizedLandmark> {
        val lm = MutableList(21) { NormalizedLandmark.create(0.5f, 0.5f, 0f) }
        lm[3] = NormalizedLandmark.create(0.45f, 0.55f, 0f)  // thumb IP
        lm[4] = NormalizedLandmark.create(0.45f, 0.70f, 0f)  // thumb TIP (below IP = pointing down)
        setFinger(lm, 5, 6, 8, 0.45f, 0.5f, extended = false)
        setFinger(lm, 9, 10, 12, 0.50f, 0.5f, extended = false)
        setFinger(lm, 13, 14, 16, 0.60f, 0.5f, extended = false)
        setFinger(lm, 17, 18, 20, 0.65f, 0.5f, extended = false)
        return lm
    }

    private fun makeFistLandmarks(): List<NormalizedLandmark> {
        val lm = MutableList(21) { NormalizedLandmark.create(0.5f, 0.5f, 0f) }
        lm[3] = NormalizedLandmark.create(0.48f, 0.40f, 0f)  // thumb IP (below tip, so thumb is not "up")
        lm[4] = NormalizedLandmark.create(0.48f, 0.48f, 0f)  // thumb TIP (close to index PIP, tucked)
        setFinger(lm, 5, 6, 8, 0.45f, 0.5f, extended = false)
        setFinger(lm, 9, 10, 12, 0.50f, 0.5f, extended = false)
        setFinger(lm, 13, 14, 16, 0.60f, 0.5f, extended = false)
        setFinger(lm, 17, 18, 20, 0.65f, 0.5f, extended = false)
        return lm
    }

    private fun activateSystem() {
        gesture.process(makeOpenHandLandmarks())
        assertThat(gesture.state).isEqualTo(PinchVolumeGesture.State.ACTIVATING)
        fakeTime += PinchVolumeGesture.ACTIVATION_HOLD_MS
        gesture.process(makeOpenHandLandmarks())
        assertThat(gesture.isActivated).isTrue()
    }

    private fun pinchClose(y: Float = 0.5f, x: Float = 0.5f) =
        makeLandmarks(x, y, x + 0.02f, y + 0.02f)

    private fun fingersOpen() = makeLandmarks(0.3f, 0.3f, 0.6f, 0.6f)

    /** Feeds the same held pose for the number of frames the state machine requires to confirm it. */
    private fun confirmEntry(landmarks: List<NormalizedLandmark>) {
        repeat(PinchVolumeGesture.POSE_CONFIRM_FRAMES) {
            gesture.process(landmarks)
        }
    }

    private fun pinchDown(y: Float = 0.5f, x: Float = 0.5f) {
        confirmEntry(pinchClose(y, x))
    }

    private fun enterVolumeMode(startY: Float = 0.5f) {
        pinchDown(startY)
        fakeTime += 400
        gesture.process(pinchClose(startY))
    }

    // ============================
    // Activation system
    // ============================

    @Test
    fun `starts in INACTIVE state`() {
        assertThat(gesture.state).isEqualTo(PinchVolumeGesture.State.INACTIVE)
        assertThat(gesture.isActivated).isFalse()
    }

    @Test
    fun `open hand starts activation`() {
        gesture.process(makeOpenHandLandmarks())
        assertThat(gesture.state).isEqualTo(PinchVolumeGesture.State.ACTIVATING)
    }

    @Test
    fun `activation completes after hold duration`() {
        activateSystem()
        assertThat(gesture.state).isEqualTo(PinchVolumeGesture.State.IDLE)
    }

    @Test
    fun `activation resets when hand closes`() {
        gesture.process(makeOpenHandLandmarks())
        assertThat(gesture.state).isEqualTo(PinchVolumeGesture.State.ACTIVATING)
        gesture.process(pinchClose())
        assertThat(gesture.state).isEqualTo(PinchVolumeGesture.State.INACTIVE)
    }

    @Test
    fun `deactivates after timeout`() {
        activateSystem()
        fakeTime += PinchVolumeGesture.DEACTIVATION_TIMEOUT_MS + 1
        gesture.process(fingersOpen())
        assertThat(gesture.isActivated).isFalse()
    }

    @Test
    fun `pinch ignored when inactive`() {
        val result = gesture.process(pinchClose())
        assertThat(result).isEqualTo(GestureAction.None)
        assertThat(gesture.state).isEqualTo(PinchVolumeGesture.State.INACTIVE)
    }

    @Test
    fun `activation progress is calculated`() {
        gesture.process(makeOpenHandLandmarks())
        fakeTime += 750
        assertThat(gesture.activationProgress).isWithin(0.01f).of(0.5f)
    }

    // ============================
    // Tap detection — Media mode
    // ============================

    @Test
    fun `single tap returns PlayPause in media mode`() {
        activateSystem()
        pinchDown()
        fakeTime += 100
        gesture.process(fingersOpen())
        fakeTime += 500
        val result = gesture.process(fingersOpen())
        assertThat(result).isEqualTo(GestureAction.PlayPause)
    }

    @Test
    fun `double tap returns NextTrack in media mode`() {
        activateSystem()
        pinchDown()
        fakeTime += 100
        gesture.process(fingersOpen())
        fakeTime += 200
        gesture.process(pinchClose())
        fakeTime += 100
        gesture.process(fingersOpen())
        fakeTime += 500
        val result = gesture.process(fingersOpen())
        assertThat(result).isEqualTo(GestureAction.NextTrack)
    }

    @Test
    fun `triple tap returns PrevTrack in media mode`() {
        activateSystem()
        pinchDown()
        fakeTime += 100
        gesture.process(fingersOpen())
        fakeTime += 200
        gesture.process(pinchClose())
        fakeTime += 100
        gesture.process(fingersOpen())
        fakeTime += 200
        gesture.process(pinchClose())
        fakeTime += 100
        gesture.process(fingersOpen())
        fakeTime += 500
        val result = gesture.process(fingersOpen())
        assertThat(result).isEqualTo(GestureAction.PrevTrack)
    }

    @Test
    fun `long hold does not count as tap`() {
        activateSystem()
        pinchDown()
        fakeTime += 400
        gesture.process(fingersOpen())
        fakeTime += 500
        val result = gesture.process(fingersOpen())
        assertThat(result).isEqualTo(GestureAction.None)
    }

    // ============================
    // Tap detection — Control mode
    // ============================

    @Test
    fun `single tap returns TapScreen in control mode`() {
        activateSystem()
        gesture.setMode(ControlMode.CONTROL)
        pinchDown()
        fakeTime += 100
        gesture.process(fingersOpen())
        fakeTime += 500
        val result = gesture.process(fingersOpen())
        assertThat(result).isEqualTo(GestureAction.TapScreen)
    }

    @Test
    fun `double tap returns GoBack in control mode`() {
        activateSystem()
        gesture.setMode(ControlMode.CONTROL)
        pinchDown()
        fakeTime += 100
        gesture.process(fingersOpen())
        fakeTime += 200
        gesture.process(pinchClose())
        fakeTime += 100
        gesture.process(fingersOpen())
        fakeTime += 500
        val result = gesture.process(fingersOpen())
        assertThat(result).isEqualTo(GestureAction.GoBack)
    }

    @Test
    fun `triple tap returns GoHome in control mode`() {
        activateSystem()
        gesture.setMode(ControlMode.CONTROL)
        pinchDown()
        fakeTime += 100
        gesture.process(fingersOpen())
        fakeTime += 200
        gesture.process(pinchClose())
        fakeTime += 100
        gesture.process(fingersOpen())
        fakeTime += 200
        gesture.process(pinchClose())
        fakeTime += 100
        gesture.process(fingersOpen())
        fakeTime += 500
        val result = gesture.process(fingersOpen())
        assertThat(result).isEqualTo(GestureAction.GoHome)
    }

    // ============================
    // Smooth volume (hold + move)
    // ============================

    @Test
    fun `volume mode returns VolumeSet with positive delta when moving up`() {
        activateSystem()
        enterVolumeMode(0.6f)
        val result = gesture.process(pinchClose(0.4f))
        assertThat(result).isInstanceOf(GestureAction.VolumeSet::class.java)
        assertThat((result as GestureAction.VolumeSet).deltaY).isGreaterThan(0f)
    }

    @Test
    fun `volume mode returns VolumeSet with negative delta when moving down`() {
        activateSystem()
        enterVolumeMode(0.4f)
        val result = gesture.process(pinchClose(0.6f))
        assertThat(result).isInstanceOf(GestureAction.VolumeSet::class.java)
        assertThat((result as GestureAction.VolumeSet).deltaY).isLessThan(0f)
    }

    @Test
    fun `volume mode returns VolumeSet every frame while pinching`() {
        activateSystem()
        enterVolumeMode(0.5f)
        val r1 = gesture.process(pinchClose(0.5f))
        val r2 = gesture.process(pinchClose(0.5f))
        assertThat(r1).isInstanceOf(GestureAction.VolumeSet::class.java)
        assertThat(r2).isInstanceOf(GestureAction.VolumeSet::class.java)
    }

    @Test
    fun `release from volume mode goes to idle`() {
        activateSystem()
        enterVolumeMode(0.5f)
        gesture.process(fingersOpen())
        assertThat(gesture.state).isEqualTo(PinchVolumeGesture.State.IDLE)
    }

    // ============================
    // Control mode smooth scroll (hold + drag)
    // ============================

    @Test
    fun `control mode returns ScreenScroll with negative deltaY when moving up`() {
        activateSystem()
        gesture.setMode(ControlMode.CONTROL)
        enterVolumeMode(0.7f)
        val result = gesture.process(pinchClose(0.4f))
        assertThat(result).isInstanceOf(GestureAction.ScreenScroll::class.java)
        assertThat((result as GestureAction.ScreenScroll).deltaY).isLessThan(0f)
    }

    @Test
    fun `control mode returns ScreenScroll with positive deltaY when moving down`() {
        activateSystem()
        gesture.setMode(ControlMode.CONTROL)
        enterVolumeMode(0.3f)
        val result = gesture.process(pinchClose(0.65f))
        assertThat(result).isInstanceOf(GestureAction.ScreenScroll::class.java)
        assertThat((result as GestureAction.ScreenScroll).deltaY).isGreaterThan(0f)
    }

    @Test
    fun `control mode horizontal swipe returns SwipeLeft mirrored and exits volume mode`() {
        activateSystem()
        gesture.setMode(ControlMode.CONTROL)
        pinchDown(0.5f, 0.5f)
        fakeTime += 400
        gesture.process(pinchClose(0.5f, 0.5f))
        val result = gesture.process(pinchClose(0.5f, 0.7f))
        assertThat(result).isEqualTo(GestureAction.SwipeLeft)
        assertThat(gesture.state).isEqualTo(PinchVolumeGesture.State.IDLE)
    }

    @Test
    fun `control mode horizontal swipe returns SwipeRight mirrored and exits volume mode`() {
        activateSystem()
        gesture.setMode(ControlMode.CONTROL)
        pinchDown(0.5f, 0.5f)
        fakeTime += 400
        gesture.process(pinchClose(0.5f, 0.5f))
        val result = gesture.process(pinchClose(0.5f, 0.3f))
        assertThat(result).isEqualTo(GestureAction.SwipeRight)
        assertThat(gesture.state).isEqualTo(PinchVolumeGesture.State.IDLE)
    }

    @Test
    fun `screen scroll emitted every frame while pinching in control mode`() {
        activateSystem()
        gesture.setMode(ControlMode.CONTROL)
        enterVolumeMode(0.5f)
        val r1 = gesture.process(pinchClose(0.5f))
        val r2 = gesture.process(pinchClose(0.5f))
        assertThat(r1).isInstanceOf(GestureAction.ScreenScroll::class.java)
        assertThat(r2).isInstanceOf(GestureAction.ScreenScroll::class.java)
    }

    @Test
    fun `screen scroll stays in volume mode until release`() {
        activateSystem()
        gesture.setMode(ControlMode.CONTROL)
        enterVolumeMode(0.7f)
        gesture.process(pinchClose(0.65f))
        assertThat(gesture.state).isEqualTo(PinchVolumeGesture.State.VOLUME_MODE)
        gesture.process(fingersOpen())
        assertThat(gesture.state).isEqualTo(PinchVolumeGesture.State.IDLE)
    }

    @Test
    fun `release after screen scroll does not trigger tap`() {
        activateSystem()
        gesture.setMode(ControlMode.CONTROL)
        enterVolumeMode(0.7f)
        gesture.process(pinchClose(0.65f))
        val result = gesture.process(fingersOpen())
        assertThat(result).isEqualTo(GestureAction.None)
        assertThat(gesture.state).isEqualTo(PinchVolumeGesture.State.IDLE)
    }

    @Test
    fun `screen scroll uses frame-to-frame delta`() {
        activateSystem()
        gesture.setMode(ControlMode.CONTROL)
        enterVolumeMode(0.5f)
        val r1 = gesture.process(pinchClose(0.4f))
        val r2 = gesture.process(pinchClose(0.3f))
        assertThat(r1).isInstanceOf(GestureAction.ScreenScroll::class.java)
        assertThat(r2).isInstanceOf(GestureAction.ScreenScroll::class.java)
        val delta1 = (r1 as GestureAction.ScreenScroll).deltaY
        val delta2 = (r2 as GestureAction.ScreenScroll).deltaY
        assertThat(delta1).isWithin(0.02f).of(delta2)
    }

    // ============================
    // Mode switching (peace sign)
    // ============================

    @Test
    fun `peace sign starts mode switching`() {
        activateSystem()
        confirmEntry(makePeaceSignLandmarks())
        assertThat(gesture.state).isEqualTo(PinchVolumeGesture.State.MODE_SWITCHING)
    }

    @Test
    fun `mode switch toggles MEDIA to CONTROL`() {
        activateSystem()
        assertThat(gesture.controlMode).isEqualTo(ControlMode.MEDIA)
        confirmEntry(makePeaceSignLandmarks())
        fakeTime += PinchVolumeGesture.MODE_SWITCH_HOLD_MS
        val result = gesture.process(makePeaceSignLandmarks())
        assertThat(result).isEqualTo(GestureAction.ModeSwitch)
        assertThat(gesture.controlMode).isEqualTo(ControlMode.CONTROL)
    }

    @Test
    fun `mode switch toggles CONTROL back to MEDIA`() {
        activateSystem()
        gesture.setMode(ControlMode.CONTROL)
        confirmEntry(makePeaceSignLandmarks())
        fakeTime += PinchVolumeGesture.MODE_SWITCH_HOLD_MS
        val result = gesture.process(makePeaceSignLandmarks())
        assertThat(result).isEqualTo(GestureAction.ModeSwitch)
        assertThat(gesture.controlMode).isEqualTo(ControlMode.MEDIA)
    }

    @Test
    fun `full mode toggle MEDIA to CONTROL back to MEDIA`() {
        activateSystem()
        assertThat(gesture.controlMode).isEqualTo(ControlMode.MEDIA)

        confirmEntry(makePeaceSignLandmarks())
        fakeTime += PinchVolumeGesture.MODE_SWITCH_HOLD_MS
        gesture.process(makePeaceSignLandmarks())
        assertThat(gesture.controlMode).isEqualTo(ControlMode.CONTROL)

        confirmEntry(makePeaceSignLandmarks())
        fakeTime += PinchVolumeGesture.MODE_SWITCH_HOLD_MS
        gesture.process(makePeaceSignLandmarks())
        assertThat(gesture.controlMode).isEqualTo(ControlMode.MEDIA)
    }

    @Test
    fun `mode switch cancels when peace sign lost`() {
        activateSystem()
        confirmEntry(makePeaceSignLandmarks())
        assertThat(gesture.state).isEqualTo(PinchVolumeGesture.State.MODE_SWITCHING)
        gesture.process(fingersOpen())
        assertThat(gesture.state).isEqualTo(PinchVolumeGesture.State.IDLE)
    }

    @Test
    fun `mode switch progress is calculated`() {
        activateSystem()
        confirmEntry(makePeaceSignLandmarks())
        fakeTime += 750
        assertThat(gesture.modeSwitchProgress).isWithin(0.01f).of(0.5f)
    }

    @Test
    fun `peace sign ignored when inactive`() {
        gesture.process(makePeaceSignLandmarks())
        assertThat(gesture.state).isNotEqualTo(PinchVolumeGesture.State.MODE_SWITCHING)
    }

    @Test
    fun `moving hand while forming pose resets confirmation and blocks mode switch`() {
        activateSystem()
        val base = makePeaceSignLandmarks()
        // shift the whole hand between frames so the "held still" check keeps failing
        gesture.process(base.map { NormalizedLandmark.create(it.x(), it.y(), it.z()) })
        gesture.process(base.map { NormalizedLandmark.create(it.x() + 0.2f, it.y(), it.z()) })
        gesture.process(base.map { NormalizedLandmark.create(it.x() + 0.4f, it.y(), it.z()) })
        assertThat(gesture.state).isNotEqualTo(PinchVolumeGesture.State.MODE_SWITCHING)
    }

    // ============================
    // Thumbs up/down gestures
    // ============================

    @Test
    fun `thumbs up held triggers RecentApps`() {
        activateSystem()
        confirmEntry(makeThumbsUpLandmarks())
        assertThat(gesture.state).isEqualTo(PinchVolumeGesture.State.THUMB_GESTURE)
        fakeTime += PinchVolumeGesture.THUMB_HOLD_MS
        val result = gesture.process(makeThumbsUpLandmarks())
        assertThat(result).isEqualTo(GestureAction.RecentApps)
        assertThat(gesture.state).isEqualTo(PinchVolumeGesture.State.IDLE)
    }

    @Test
    fun `thumbs down held triggers PreviousApp`() {
        activateSystem()
        confirmEntry(makeThumbsDownLandmarks())
        assertThat(gesture.state).isEqualTo(PinchVolumeGesture.State.THUMB_GESTURE)
        fakeTime += PinchVolumeGesture.THUMB_HOLD_MS
        val result = gesture.process(makeThumbsDownLandmarks())
        assertThat(result).isEqualTo(GestureAction.PreviousApp)
        assertThat(gesture.state).isEqualTo(PinchVolumeGesture.State.IDLE)
    }

    @Test
    fun `thumb gesture cancels when pose lost`() {
        activateSystem()
        confirmEntry(makeThumbsUpLandmarks())
        assertThat(gesture.state).isEqualTo(PinchVolumeGesture.State.THUMB_GESTURE)
        gesture.process(fingersOpen())
        assertThat(gesture.state).isEqualTo(PinchVolumeGesture.State.IDLE)
    }

    @Test
    fun `thumb gesture progress is calculated`() {
        activateSystem()
        confirmEntry(makeThumbsUpLandmarks())
        fakeTime += 400
        assertThat(gesture.thumbGestureProgress).isWithin(0.01f).of(0.5f)
    }

    @Test
    fun `thumbs up works in both modes`() {
        activateSystem()
        gesture.setMode(ControlMode.MEDIA)
        confirmEntry(makeThumbsUpLandmarks())
        assertThat(gesture.state).isEqualTo(PinchVolumeGesture.State.THUMB_GESTURE)
        gesture.process(fingersOpen())

        gesture.setMode(ControlMode.CONTROL)
        confirmEntry(makeThumbsUpLandmarks())
        assertThat(gesture.state).isEqualTo(PinchVolumeGesture.State.THUMB_GESTURE)
    }

    // ============================
    // Cursor mode (fist)
    // ============================

    @Test
    fun `fist enters cursor mode in control mode`() {
        activateSystem()
        gesture.setMode(ControlMode.CONTROL)
        confirmEntry(makeFistLandmarks())
        assertThat(gesture.state).isEqualTo(PinchVolumeGesture.State.CURSOR_MODE)
    }

    @Test
    fun `fist requires multiple confirm frames before entering cursor mode`() {
        activateSystem()
        gesture.setMode(ControlMode.CONTROL)
        repeat(PinchVolumeGesture.POSE_CONFIRM_FRAMES - 1) {
            gesture.process(makeFistLandmarks())
        }
        assertThat(gesture.state).isNotEqualTo(PinchVolumeGesture.State.CURSOR_MODE)
    }

    @Test
    fun `fist does not enter cursor mode in media mode`() {
        activateSystem()
        gesture.setMode(ControlMode.MEDIA)
        gesture.process(makeFistLandmarks())
        assertThat(gesture.state).isNotEqualTo(PinchVolumeGesture.State.CURSOR_MODE)
    }

    @Test
    fun `cursor mode emits CursorMove`() {
        activateSystem()
        gesture.setMode(ControlMode.CONTROL)
        confirmEntry(makeFistLandmarks())
        val result = gesture.process(makeFistLandmarks())
        assertThat(result).isInstanceOf(GestureAction.CursorMove::class.java)
    }

    @Test
    fun `releasing fist stores cursor position`() {
        activateSystem()
        gesture.setMode(ControlMode.CONTROL)
        confirmEntry(makeFistLandmarks())
        assertThat(gesture.state).isEqualTo(PinchVolumeGesture.State.CURSOR_MODE)

        gesture.process(fingersOpen())
        assertThat(gesture.state).isEqualTo(PinchVolumeGesture.State.IDLE)
        assertThat(gesture.hasCursorPosition).isTrue()
    }

    @Test
    fun `tap after cursor uses TapAt with stored position`() {
        activateSystem()
        gesture.setMode(ControlMode.CONTROL)

        confirmEntry(makeFistLandmarks())
        gesture.process(fingersOpen())
        assertThat(gesture.hasCursorPosition).isTrue()

        pinchDown()
        fakeTime += 100
        gesture.process(fingersOpen())
        fakeTime += 500
        val result = gesture.process(fingersOpen())
        assertThat(result).isInstanceOf(GestureAction.TapAt::class.java)
        assertThat(gesture.hasCursorPosition).isFalse()
    }

    @Test
    fun `tap without cursor uses TapScreen`() {
        activateSystem()
        gesture.setMode(ControlMode.CONTROL)
        assertThat(gesture.hasCursorPosition).isFalse()

        pinchDown()
        fakeTime += 100
        gesture.process(fingersOpen())
        fakeTime += 500
        val result = gesture.process(fingersOpen())
        assertThat(result).isEqualTo(GestureAction.TapScreen)
    }

    @Test
    fun `cursor mode resets on hand loss`() {
        activateSystem()
        gesture.setMode(ControlMode.CONTROL)
        confirmEntry(makeFistLandmarks())
        assertThat(gesture.state).isEqualTo(PinchVolumeGesture.State.CURSOR_MODE)

        val small = List(10) { NormalizedLandmark.create(0.5f, 0.5f, 0f) }
        gesture.process(small)
        assertThat(gesture.state).isEqualTo(PinchVolumeGesture.State.IDLE)
    }

    // ============================
    // Pose detection
    // ============================

    @Test
    fun `isPeaceSign detects valid peace sign`() {
        assertThat(gesture.isPeaceSign(makePeaceSignLandmarks())).isTrue()
    }

    @Test
    fun `isPeaceSign rejects open hand`() {
        assertThat(gesture.isPeaceSign(makeOpenHandLandmarks())).isFalse()
    }

    @Test
    fun `isPeaceSign rejects closed fist`() {
        assertThat(gesture.isPeaceSign(pinchClose())).isFalse()
    }

    @Test
    fun `isPeaceSign returns false for insufficient landmarks`() {
        val small = List(10) { NormalizedLandmark.create(0.5f, 0.5f, 0f) }
        assertThat(gesture.isPeaceSign(small)).isFalse()
    }

    @Test
    fun `isPeaceSign detects extended fingers regardless of hand rotation`() {
        // same peace-sign shape as makePeaceSignLandmarks, but rotated 90 degrees:
        // fingers point sideways (varying x, constant y) instead of up (constant x, varying y)
        val lm = MutableList(21) { NormalizedLandmark.create(0.5f, 0.5f, 0f) }
        lm[5] = NormalizedLandmark.create(0.60f, 0.40f, 0f)
        lm[6] = NormalizedLandmark.create(0.52f, 0.40f, 0f)
        lm[8] = NormalizedLandmark.create(0.40f, 0.40f, 0f)
        lm[9] = NormalizedLandmark.create(0.60f, 0.50f, 0f)
        lm[10] = NormalizedLandmark.create(0.52f, 0.50f, 0f)
        lm[12] = NormalizedLandmark.create(0.40f, 0.50f, 0f)
        lm[13] = NormalizedLandmark.create(0.60f, 0.60f, 0f)
        lm[14] = NormalizedLandmark.create(0.54f, 0.60f, 0f)
        lm[16] = NormalizedLandmark.create(0.62f, 0.60f, 0f)
        lm[17] = NormalizedLandmark.create(0.60f, 0.70f, 0f)
        lm[18] = NormalizedLandmark.create(0.54f, 0.70f, 0f)
        lm[20] = NormalizedLandmark.create(0.62f, 0.70f, 0f)
        assertThat(gesture.isPeaceSign(lm)).isTrue()
    }

    @Test
    fun `isThumbsUp detects valid thumbs up`() {
        assertThat(gesture.isThumbsUp(makeThumbsUpLandmarks())).isTrue()
    }

    @Test
    fun `isThumbsUp rejects open hand`() {
        assertThat(gesture.isThumbsUp(makeOpenHandLandmarks())).isFalse()
    }

    @Test
    fun `isThumbsDown detects valid thumbs down`() {
        assertThat(gesture.isThumbsDown(makeThumbsDownLandmarks())).isTrue()
    }

    @Test
    fun `isFist detects valid fist`() {
        assertThat(gesture.isFist(makeFistLandmarks())).isTrue()
    }

    @Test
    fun `isFist rejects open hand`() {
        assertThat(gesture.isFist(makeOpenHandLandmarks())).isFalse()
    }

    @Test
    fun `isFist rejects thumbs up`() {
        assertThat(gesture.isFist(makeThumbsUpLandmarks())).isFalse()
    }

    // ============================
    // Edge cases
    // ============================

    @Test
    fun `returns None for insufficient landmarks`() {
        val landmarks = List(10) { NormalizedLandmark.create(0.5f, 0.5f, 0f) }
        assertThat(gesture.process(landmarks)).isEqualTo(GestureAction.None)
    }

    @Test
    fun `returns None for empty landmarks`() {
        assertThat(gesture.process(emptyList())).isEqualTo(GestureAction.None)
    }

    @Test
    fun `reset clears state to inactive`() {
        activateSystem()
        gesture.process(pinchClose())
        gesture.reset()
        assertThat(gesture.state).isEqualTo(PinchVolumeGesture.State.INACTIVE)
    }

    @Test
    fun `tap timeout checked on insufficient landmarks`() {
        activateSystem()
        pinchDown()
        fakeTime += 100
        gesture.process(fingersOpen())
        fakeTime += 500
        val landmarks = List(10) { NormalizedLandmark.create(0.5f, 0.5f, 0f) }
        val result = gesture.process(landmarks)
        assertThat(result).isEqualTo(GestureAction.PlayPause)
    }

    @Test
    fun `open hand detection works correctly`() {
        assertThat(gesture.isOpenHand(makeOpenHandLandmarks())).isTrue()
    }

    @Test
    fun `closed hand is not open hand`() {
        assertThat(gesture.isOpenHand(pinchClose())).isFalse()
    }

    @Test
    fun `deactivation clears pending taps`() {
        activateSystem()
        pinchDown()
        fakeTime += 100
        gesture.process(fingersOpen())
        assertThat(gesture.state).isEqualTo(PinchVolumeGesture.State.TAP_WAITING)

        fakeTime += PinchVolumeGesture.DEACTIVATION_TIMEOUT_MS + 1
        val result = gesture.process(fingersOpen())
        assertThat(result).isEqualTo(GestureAction.None)
        assertThat(gesture.isActivated).isFalse()
    }

    @Test
    fun `gesture activity resets deactivation timer`() {
        activateSystem()
        fakeTime += 9000
        pinchDown()
        fakeTime += 2000
        gesture.process(fingersOpen())
        assertThat(gesture.isActivated).isTrue()
    }

    @Test
    fun `activation progress zero when not activating`() {
        assertThat(gesture.activationProgress).isEqualTo(0f)
        activateSystem()
        assertThat(gesture.activationProgress).isEqualTo(0f)
    }

    @Test
    fun `mode switch progress zero when not switching`() {
        assertThat(gesture.modeSwitchProgress).isEqualTo(0f)
        activateSystem()
        assertThat(gesture.modeSwitchProgress).isEqualTo(0f)
    }

    @Test
    fun `setMode changes control mode`() {
        gesture.setMode(ControlMode.CONTROL)
        assertThat(gesture.controlMode).isEqualTo(ControlMode.CONTROL)
        gesture.setMode(ControlMode.MEDIA)
        assertThat(gesture.controlMode).isEqualTo(ControlMode.MEDIA)
    }

    @Test
    fun `starts in MEDIA mode by default`() {
        assertThat(gesture.controlMode).isEqualTo(ControlMode.MEDIA)
    }
}
