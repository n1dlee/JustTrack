package com.justtrack.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.os.Trace
import android.util.Log
import androidx.camera.core.ImageProxy
import androidx.preference.PreferenceManager
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult

/**
 * Camera frame -> MediaPipe landmarks -> [PinchVolumeGesture] -> [FrameResult].
 *
 * Owned by [GestureService] only, so there is exactly one landmarker and one
 * gesture state machine per process.
 */
class HandTrackingEngine(context: Context) {

    companion object {
        private const val TAG = "HandTrackingEngine"
        private const val MODEL_ASSET = "hand_landmarker.task"

        /**
         * MediaPipe labels handedness assuming a mirrored (selfie) image. CameraX
         * analysis frames from the front camera are not mirrored, so the label is
         * the opposite of the user's physical hand.
         */
        private const val HANDEDNESS_LABELS_MIRRORED = true
    }

    data class FrameResult(
        val landmarks: List<NormalizedLandmark>?,
        val imageWidth: Int,
        val imageHeight: Int,
        val action: GestureAction,
        val isPinching: Boolean,
        val gestureState: PinchVolumeGesture.State,
        val activationProgress: Float,
        val activationChanged: Boolean,
        val isActivated: Boolean,
        val controlMode: ControlMode,
        val modeSwitchProgress: Float,
        val enteredVolumeMode: Boolean,
        val thumbGestureProgress: Float,
        val cursorX: Float,
        val cursorY: Float,
        val isCursorActive: Boolean,
        val isPointing: Boolean
    )

    private val appContext = context.applicationContext
    @Volatile private var handLandmarker: HandLandmarker? = null
    private var configuredHands = 0
    val gestureRecognizer = PinchVolumeGesture()
    private val rotationMatrix = Matrix()
    @Volatile private var released = false
    private var wasActivated = false
    private var wasInVolumeMode = false
    private var lastTimestampMs = -1L
    @Volatile var preferredHand: String = PrefsKeys.DEFAULT_MAIN_HAND
        private set
    @Volatile var onFrameResult: ((FrameResult) -> Unit)? = null

    val isModelLoaded: Boolean get() = handLandmarker != null

    init {
        setupHandLandmarker(handsFor(preferredHand))
    }

    /** Two hands are only tracked when a specific hand is preferred, to pick it reliably. */
    private fun handsFor(preference: String): Int = if (preference == "both") 1 else 2

    private fun setupHandLandmarker(numHands: Int) {
        handLandmarker?.close()
        handLandmarker = null
        try {
            createLandmarker(Delegate.GPU, numHands)
            Log.i(TAG, "Using GPU delegate")
        } catch (e: Exception) {
            Log.w(TAG, "GPU unavailable, falling back to CPU", e)
            try {
                createLandmarker(Delegate.CPU, numHands)
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to init HandLandmarker", e2)
            }
        }
        configuredHands = numHands
    }

    private fun createLandmarker(delegate: Delegate, numHands: Int) {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET)
            .setDelegate(delegate)
            .build()

        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setMinHandDetectionConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setNumHands(numHands)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setResultListener(this::onDetectionResult)
            .setErrorListener { e ->
                if (BuildConfig.DEBUG) Log.e(TAG, "Detection error", e)
            }
            .build()

        handLandmarker = HandLandmarker.createFromOptions(appContext, options)
    }

    private fun onDetectionResult(result: HandLandmarkerResult, input: MPImage) {
        if (released) return
        Trace.beginSection("JT.gesture")
        try {
            synchronized(gestureRecognizer) {
                if (input.height > 0) gestureRecognizer.frameAspect = input.width.toFloat() / input.height
                val handIndex = if (result.landmarks().isEmpty()) -1 else findPreferredHand(result)
                if (handIndex < 0) {
                    val action = gestureRecognizer.process(emptyList())
                    onFrameResult?.invoke(makeEmptyResult(action, input))
                    return
                }

                val landmarks = result.landmarks()[handIndex]
                val action = gestureRecognizer.process(landmarks)

                val nowActivated = gestureRecognizer.isActivated
                val changed = nowActivated != wasActivated
                wasActivated = nowActivated

                val inVolumeMode = gestureRecognizer.state == PinchVolumeGesture.State.VOLUME_MODE
                val enteredVolumeMode = inVolumeMode && !wasInVolumeMode
                wasInVolumeMode = inVolumeMode

                onFrameResult?.invoke(FrameResult(
                    landmarks = landmarks,
                    imageWidth = input.width,
                    imageHeight = input.height,
                    action = action,
                    isPinching = gestureRecognizer.isPinching,
                    gestureState = gestureRecognizer.state,
                    activationProgress = gestureRecognizer.activationProgress,
                    activationChanged = changed,
                    isActivated = nowActivated,
                    controlMode = gestureRecognizer.controlMode,
                    modeSwitchProgress = gestureRecognizer.modeSwitchProgress,
                    enteredVolumeMode = enteredVolumeMode,
                    thumbGestureProgress = gestureRecognizer.thumbGestureProgress,
                    cursorX = gestureRecognizer.cursorX,
                    cursorY = gestureRecognizer.cursorY,
                    isCursorActive = gestureRecognizer.isPointing || gestureRecognizer.hasCursorPosition,
                    isPointing = gestureRecognizer.isPointing
                ))
            }
        } finally {
            Trace.endSection()
        }
    }

    fun processFrame(imageProxy: ImageProxy) {
        val landmarker = handLandmarker
        if (landmarker == null || released) {
            imageProxy.close()
            return
        }

        try {
            val finalBitmap = traced("JT.bitmap") {
                val bitmap = imageProxy.toBitmap()
                val rotation = imageProxy.imageInfo.rotationDegrees
                if (rotation != 0) {
                    rotationMatrix.reset()
                    rotationMatrix.postRotate(rotation.toFloat())
                    Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, rotationMatrix, true)
                        .also { bitmap.recycle() }
                } else {
                    bitmap
                }
            }

            traced("JT.detectSubmit") {
                val mpImage = BitmapImageBuilder(finalBitmap).build()
                var timestampMs = SystemClock.uptimeMillis()
                if (timestampMs <= lastTimestampMs) timestampMs = lastTimestampMs + 1
                lastTimestampMs = timestampMs
                landmarker.detectAsync(mpImage, timestampMs)
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e(TAG, "Frame error", e)
        } finally {
            imageProxy.close()
        }
    }

    /** Perfetto/systrace section so the per-frame pipeline cost can be measured on device. */
    private inline fun <T> traced(name: String, block: () -> T): T {
        Trace.beginSection(name)
        try {
            return block()
        } finally {
            Trace.endSection()
        }
    }

    /** Index of the hand to use: the best-scoring hand matching the preference, or -1. */
    private fun findPreferredHand(result: HandLandmarkerResult): Int {
        val handednesses = result.handednesses()
        if (preferredHand == "both") return 0

        val wantedLabel = if (HANDEDNESS_LABELS_MIRRORED) {
            if (preferredHand.equals("Left", ignoreCase = true)) "Right" else "Left"
        } else {
            preferredHand
        }

        var bestIndex = -1
        var bestScore = -1f
        for (i in handednesses.indices) {
            val category = handednesses[i].firstOrNull() ?: continue
            if (category.categoryName().equals(wantedLabel, ignoreCase = true) && category.score() > bestScore) {
                bestScore = category.score()
                bestIndex = i
            }
        }
        return bestIndex
    }

    private fun makeEmptyResult(action: GestureAction, input: MPImage): FrameResult {
        wasInVolumeMode = false
        val nowActivated = gestureRecognizer.isActivated
        val changed = nowActivated != wasActivated
        wasActivated = nowActivated
        return FrameResult(
            landmarks = null,
            imageWidth = input.width,
            imageHeight = input.height,
            action = action,
            isPinching = false,
            gestureState = gestureRecognizer.state,
            activationProgress = 0f,
            activationChanged = changed,
            isActivated = nowActivated,
            controlMode = gestureRecognizer.controlMode,
            modeSwitchProgress = 0f,
            enteredVolumeMode = false,
            thumbGestureProgress = 0f,
            cursorX = gestureRecognizer.cursorX,
            cursorY = gestureRecognizer.cursorY,
            isCursorActive = gestureRecognizer.isPointing || gestureRecognizer.hasCursorPosition,
            isPointing = gestureRecognizer.isPointing
        )
    }

    fun loadPreferences(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val pinchSens = prefs.getInt(PrefsKeys.PINCH_SENSITIVITY, PrefsKeys.DEFAULT_SENSITIVITY)
        val moveSens = prefs.getInt(PrefsKeys.MOVE_SENSITIVITY, PrefsKeys.DEFAULT_SENSITIVITY)
        val savedMode = prefs.getString(PrefsKeys.CONTROL_MODE, "MEDIA") ?: "MEDIA"
        val hand = prefs.getString(PrefsKeys.MAIN_HAND, PrefsKeys.DEFAULT_MAIN_HAND)
            ?: PrefsKeys.DEFAULT_MAIN_HAND

        synchronized(gestureRecognizer) {
            gestureRecognizer.updateThresholds(
                PrefsKeys.pinchThreshold(pinchSens),
                PrefsKeys.moveThreshold(moveSens)
            )
            gestureRecognizer.setMode(
                when (savedMode) {
                    "CONTROL", "SCREEN", "NAVIGATION" -> ControlMode.CONTROL
                    else -> ControlMode.MEDIA
                }
            )
        }

        preferredHand = hand
        val neededHands = handsFor(hand)
        if (neededHands != configuredHands && !released) setupHandLandmarker(neededHands)
    }

    fun resetGestureState() {
        synchronized(gestureRecognizer) {
            gestureRecognizer.reset()
            wasActivated = false
            wasInVolumeMode = false
        }
    }

    fun release() {
        released = true
        onFrameResult = null
        handLandmarker?.close()
        handLandmarker = null
    }
}
