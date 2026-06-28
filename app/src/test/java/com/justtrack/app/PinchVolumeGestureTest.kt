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

    private fun activateSystem() {
        gesture.process(makeOpenHandLandmarks())
        assertThat(gesture.state).isEqualTo(PinchVolumeGesture.State.ACTIVATING)
        fakeTime += PinchVolumeGesture.ACTIVATION_HOLD_MS
        gesture.process(makeOpenHandLandmarks())
        assertThat(gesture.isActivated).isTrue()
    }

    private fun pinchClose(y: Float = 0.5f) = makeLandmarks(0.5f, y, 0.52f, y + 0.02f)
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
        assertThat(result).isEqualTo(GestureAction.NONE)
        assertThat(gesture.state).isEqualTo(PinchVolumeGesture.State.INACTIVE)
    }

    @Test
    fun `activation progress is calculated`() {
        gesture.process(makeOpenHandLandmarks())
        fakeTime += 750
        assertThat(gesture.activationProgress).isWithin(0.01f).of(0.5f)
    }

    // ============================
    // Tap detection (after activation)
    // ============================

    @Test
    fun `single tap returns PLAY_PAUSE`() {
        activateSystem()
        gesture.process(pinchClose())
        fakeTime += 100
        gesture.process(fingersOpen())
        fakeTime += 500
        val result = gesture.process(fingersOpen())
        assertThat(result).isEqualTo(GestureAction.PLAY_PAUSE)
    }

    @Test
    fun `double tap returns NEXT_TRACK`() {
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
        assertThat(result).isEqualTo(GestureAction.NEXT_TRACK)
    }

    @Test
    fun `triple tap returns PREV_TRACK`() {
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
        assertThat(result).isEqualTo(GestureAction.PREV_TRACK)
    }

    @Test
    fun `long hold does not count as tap`() {
        activateSystem()
        gesture.process(pinchClose())
        fakeTime += 400
        gesture.process(fingersOpen())
        fakeTime += 500
        val result = gesture.process(fingersOpen())
        assertThat(result).isEqualTo(GestureAction.NONE)
    }

    // ============================
    // Volume control (hold + move)
    // ============================

    @Test
    fun `volume up when hold and move up`() {
        activateSystem()
        enterVolumeMode(0.6f)
        val result = gesture.process(pinchClose(0.5f))
        assertThat(result).isEqualTo(GestureAction.VOLUME_UP)
    }

    @Test
    fun `volume down when hold and move down`() {
        activateSystem()
        enterVolumeMode(0.4f)
        val result = gesture.process(pinchClose(0.55f))
        assertThat(result).isEqualTo(GestureAction.VOLUME_DOWN)
    }

    @Test
    fun `no action when hold but small movement`() {
        activateSystem()
        enterVolumeMode(0.5f)
        val result = gesture.process(pinchClose(0.51f))
        assertThat(result).isEqualTo(GestureAction.NONE)
    }

    @Test
    fun `cooldown blocks rapid volume actions`() {
        activateSystem()
        enterVolumeMode(0.6f)
        val up = gesture.process(pinchClose(0.5f))
        assertThat(up).isEqualTo(GestureAction.VOLUME_UP)

        val blocked = gesture.process(pinchClose(0.4f))
        assertThat(blocked).isEqualTo(GestureAction.NONE)
    }

    @Test
    fun `volume action allowed after cooldown`() {
        activateSystem()
        enterVolumeMode(0.6f)
        gesture.process(pinchClose(0.5f))

        repeat(1) { gesture.process(pinchClose(0.5f)) }

        val result = gesture.process(pinchClose(0.4f))
        assertThat(result).isEqualTo(GestureAction.VOLUME_UP)
    }

    @Test
    fun `release from volume mode goes to idle`() {
        activateSystem()
        enterVolumeMode(0.5f)
        gesture.process(fingersOpen())
        val result = gesture.process(pinchClose(0.5f))
        assertThat(result).isEqualTo(GestureAction.NONE)
    }

    @Test
    fun `multiple volume changes in sequence`() {
        activateSystem()
        enterVolumeMode(0.5f)

        val up = gesture.process(pinchClose(0.4f))
        assertThat(up).isEqualTo(GestureAction.VOLUME_UP)
        repeat(1) { gesture.process(pinchClose(0.4f)) }

        val down = gesture.process(pinchClose(0.55f))
        assertThat(down).isEqualTo(GestureAction.VOLUME_DOWN)
        repeat(1) { gesture.process(pinchClose(0.55f)) }

        val up2 = gesture.process(pinchClose(0.45f))
        assertThat(up2).isEqualTo(GestureAction.VOLUME_UP)
    }

    // ============================
    // Edge cases
    // ============================

    @Test
    fun `returns NONE for insufficient landmarks`() {
        val landmarks = List(10) { NormalizedLandmark.create(0.5f, 0.5f, 0f) }
        assertThat(gesture.process(landmarks)).isEqualTo(GestureAction.NONE)
    }

    @Test
    fun `returns NONE for empty landmarks`() {
        assertThat(gesture.process(emptyList())).isEqualTo(GestureAction.NONE)
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
        assertThat(result).isEqualTo(GestureAction.PLAY_PAUSE)
    }

    @Test
    fun `open hand detection works correctly`() {
        assertThat(gesture.isOpenHand(makeOpenHandLandmarks())).isTrue()
    }

    @Test
    fun `closed hand is not open hand`() {
        assertThat(gesture.isOpenHand(pinchClose())).isFalse()
    }
}
