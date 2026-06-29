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

    private fun makePeaceSignLandmarks(): List<NormalizedLandmark> {
        return List(21) { i ->
            when (i) {
                6 -> NormalizedLandmark.create(0.35f, 0.4f, 0f)   // index PIP
                8 -> NormalizedLandmark.create(0.35f, 0.2f, 0f)   // index TIP (above PIP)
                10 -> NormalizedLandmark.create(0.50f, 0.4f, 0f)  // middle PIP
                12 -> NormalizedLandmark.create(0.50f, 0.2f, 0f)  // middle TIP (above PIP)
                14 -> NormalizedLandmark.create(0.60f, 0.4f, 0f)  // ring PIP
                16 -> NormalizedLandmark.create(0.60f, 0.6f, 0f)  // ring TIP (below PIP = folded)
                18 -> NormalizedLandmark.create(0.70f, 0.4f, 0f)  // pinky PIP
                20 -> NormalizedLandmark.create(0.70f, 0.6f, 0f)  // pinky TIP (below PIP = folded)
                else -> NormalizedLandmark.create(0.5f, 0.5f, 0f)
            }
        }
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

    private fun enterVolumeMode(startY: Float = 0.5f) {
        gesture.process(pinchClose(startY))
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
        gesture.process(pinchClose())
        fakeTime += 100
        gesture.process(fingersOpen())
        fakeTime += 500
        val result = gesture.process(fingersOpen())
        assertThat(result).isEqualTo(GestureAction.PlayPause)
    }

    @Test
    fun `double tap returns NextTrack in media mode`() {
        activateSystem()
        gesture.process(pinchClose())
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
        gesture.process(pinchClose())
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
        gesture.process(pinchClose())
        fakeTime += 400
        gesture.process(fingersOpen())
        fakeTime += 500
        val result = gesture.process(fingersOpen())
        assertThat(result).isEqualTo(GestureAction.None)
    }

    // ============================
    // Tap detection — Screen mode
    // ============================

    @Test
    fun `single tap returns TapScreen in screen mode`() {
        activateSystem()
        gesture.setMode(ControlMode.SCREEN)
        gesture.process(pinchClose())
        fakeTime += 100
        gesture.process(fingersOpen())
        fakeTime += 500
        val result = gesture.process(fingersOpen())
        assertThat(result).isEqualTo(GestureAction.TapScreen)
    }

    @Test
    fun `double tap returns DoubleTapScreen in screen mode`() {
        activateSystem()
        gesture.setMode(ControlMode.SCREEN)
        gesture.process(pinchClose())
        fakeTime += 100
        gesture.process(fingersOpen())
        fakeTime += 200
        gesture.process(pinchClose())
        fakeTime += 100
        gesture.process(fingersOpen())
        fakeTime += 500
        val result = gesture.process(fingersOpen())
        assertThat(result).isEqualTo(GestureAction.DoubleTapScreen)
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
    // Screen mode swipes (hold + drag)
    // ============================

    @Test
    fun `drag up returns SwipeUp in screen mode`() {
        activateSystem()
        gesture.setMode(ControlMode.SCREEN)
        enterVolumeMode(0.7f)
        val result = gesture.process(pinchClose(0.4f))
        assertThat(result).isEqualTo(GestureAction.SwipeUp)
    }

    @Test
    fun `drag down returns SwipeDown in screen mode`() {
        activateSystem()
        gesture.setMode(ControlMode.SCREEN)
        enterVolumeMode(0.3f)
        val result = gesture.process(pinchClose(0.65f))
        assertThat(result).isEqualTo(GestureAction.SwipeDown)
    }

    @Test
    fun `drag left returns SwipeLeft in screen mode`() {
        activateSystem()
        gesture.setMode(ControlMode.SCREEN)
        gesture.process(pinchClose(0.5f, 0.5f))
        fakeTime += 400
        gesture.process(pinchClose(0.5f, 0.5f))
        val result = gesture.process(pinchClose(0.5f, 0.3f))
        assertThat(result).isEqualTo(GestureAction.SwipeLeft)
    }

    @Test
    fun `drag right returns SwipeRight in screen mode`() {
        activateSystem()
        gesture.setMode(ControlMode.SCREEN)
        gesture.process(pinchClose(0.5f, 0.5f))
        fakeTime += 400
        gesture.process(pinchClose(0.5f, 0.5f))
        val result = gesture.process(pinchClose(0.5f, 0.7f))
        assertThat(result).isEqualTo(GestureAction.SwipeRight)
    }

    @Test
    fun `screen swipe stays in volume mode until release`() {
        activateSystem()
        gesture.setMode(ControlMode.SCREEN)
        enterVolumeMode(0.7f)
        gesture.process(pinchClose(0.4f))
        assertThat(gesture.state).isEqualTo(PinchVolumeGesture.State.VOLUME_MODE)
        gesture.process(fingersOpen())
        assertThat(gesture.state).isEqualTo(PinchVolumeGesture.State.IDLE)
    }

    @Test
    fun `release after swipe does not trigger tap`() {
        activateSystem()
        gesture.setMode(ControlMode.SCREEN)
        enterVolumeMode(0.7f)
        gesture.process(pinchClose(0.4f))
        val result = gesture.process(fingersOpen())
        assertThat(result).isEqualTo(GestureAction.None)
        assertThat(gesture.state).isEqualTo(PinchVolumeGesture.State.IDLE)
    }

    @Test
    fun `small movement in screen mode returns None`() {
        activateSystem()
        gesture.setMode(ControlMode.SCREEN)
        enterVolumeMode(0.5f)
        val result = gesture.process(pinchClose(0.51f))
        assertThat(result).isEqualTo(GestureAction.None)
    }

    // ============================
    // Mode switching (peace sign)
    // ============================

    @Test
    fun `peace sign starts mode switching`() {
        activateSystem()
        gesture.process(makePeaceSignLandmarks())
        assertThat(gesture.state).isEqualTo(PinchVolumeGesture.State.MODE_SWITCHING)
    }

    @Test
    fun `mode switch completes after hold duration`() {
        activateSystem()
        assertThat(gesture.controlMode).isEqualTo(ControlMode.MEDIA)
        gesture.process(makePeaceSignLandmarks())
        fakeTime += PinchVolumeGesture.MODE_SWITCH_HOLD_MS
        val result = gesture.process(makePeaceSignLandmarks())
        assertThat(result).isEqualTo(GestureAction.ModeSwitch)
        assertThat(gesture.controlMode).isEqualTo(ControlMode.SCREEN)
    }

    @Test
    fun `mode switch toggles back to media`() {
        activateSystem()
        gesture.setMode(ControlMode.SCREEN)
        gesture.process(makePeaceSignLandmarks())
        fakeTime += PinchVolumeGesture.MODE_SWITCH_HOLD_MS
        val result = gesture.process(makePeaceSignLandmarks())
        assertThat(result).isEqualTo(GestureAction.ModeSwitch)
        assertThat(gesture.controlMode).isEqualTo(ControlMode.MEDIA)
    }

    @Test
    fun `mode switch cancels when peace sign lost`() {
        activateSystem()
        gesture.process(makePeaceSignLandmarks())
        assertThat(gesture.state).isEqualTo(PinchVolumeGesture.State.MODE_SWITCHING)
        gesture.process(fingersOpen())
        assertThat(gesture.state).isEqualTo(PinchVolumeGesture.State.IDLE)
    }

    @Test
    fun `mode switch progress is calculated`() {
        activateSystem()
        gesture.process(makePeaceSignLandmarks())
        fakeTime += 750
        assertThat(gesture.modeSwitchProgress).isWithin(0.01f).of(0.5f)
    }

    @Test
    fun `peace sign ignored when inactive`() {
        gesture.process(makePeaceSignLandmarks())
        assertThat(gesture.state).isNotEqualTo(PinchVolumeGesture.State.MODE_SWITCHING)
    }

    // ============================
    // Peace sign detection
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
        gesture.process(pinchClose())
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
        gesture.process(pinchClose())
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
        gesture.process(pinchClose())
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
        gesture.setMode(ControlMode.SCREEN)
        assertThat(gesture.controlMode).isEqualTo(ControlMode.SCREEN)
        gesture.setMode(ControlMode.MEDIA)
        assertThat(gesture.controlMode).isEqualTo(ControlMode.MEDIA)
    }

    @Test
    fun `starts in MEDIA mode by default`() {
        assertThat(gesture.controlMode).isEqualTo(ControlMode.MEDIA)
    }
}
