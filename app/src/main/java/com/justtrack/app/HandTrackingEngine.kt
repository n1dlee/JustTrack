package com.justtrack.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
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

class HandTrackingEngine(context: Context) {

    companion object {
        private const val TAG = "HandTrackingEngine"
        private const val MODEL_ASSET = "hand_landmarker.task"
        private const val THUMB_TIP = 4
        private const val INDEX_TIP = 8
    }

    data class FrameResult(
        val landmarks: List<NormalizedLandmark>?,
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
        val isCursorActive: Boolean
    )

    private var handLandmarker: HandLandmarker? = null
    val gestureRecognizer = PinchVolumeGesture()
    private val rotationMatrix = Matrix()
    @Volatile private var wasActivated = false
    private var wasInVolumeMode = false
    private var lastTimestampMs = -1L
    var preferredHand: String = PrefsKeys.DEFAULT_MAIN_HAND
    var onFrameResult: ((FrameResult) -> Unit)? = null

    val isModelLoaded: Boolean get() = handLandmarker != null

    init {
        setupHandLandmarker(context)
    }

    private fun setupHandLandmarker(context: Context) {
        try {
            createLandmarker(context, Delegate.GPU)
            Log.i(TAG, "Using GPU delegate")
        } catch (e: Exception) {
            Log.w(TAG, "GPU unavailable, falling back to CPU", e)
            try {
                createLandmarker(context, Delegate.CPU)
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to init HandLandmarker", e2)
            }
        }
    }

    private fun createLandmarker(context: Context, delegate: Delegate) {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET)
            .setDelegate(delegate)
            .build()

        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setMinHandDetectionConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setNumHands(1)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setResultListener(this::onDetectionResult)
            .setErrorListener { e ->
                if (BuildConfig.DEBUG) Log.e(TAG, "Detection error", e)
            }
            .build()

        handLandmarker = HandLandmarker.createFromOptions(context, options)
    }

    private fun onDetectionResult(result: HandLandmarkerResult, @Suppress("UNUSED_PARAMETER") input: MPImage) {
        synchronized(gestureRecognizer) {
            if (result.landmarks().isNotEmpty()) {
                val handIndex = findPreferredHand(result)
                if (handIndex < 0) {
                    gestureRecognizer.process(emptyList())
                    onFrameResult?.invoke(makeEmptyResult())
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

                val thumbTip = landmarks[THUMB_TIP]
                val indexTip = landmarks[INDEX_TIP]
                val dx = thumbTip.x() - indexTip.x()
                val dy = thumbTip.y() - indexTip.y()
                val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                val pinching = dist < PinchVolumeGesture.DEFAULT_PINCH_THRESHOLD

                onFrameResult?.invoke(FrameResult(
                    landmarks = landmarks,
                    action = action,
                    isPinching = pinching,
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
                    isCursorActive = gestureRecognizer.state == PinchVolumeGesture.State.CURSOR_MODE || gestureRecognizer.hasCursorPosition
                ))
            } else {
                gestureRecognizer.process(emptyList())
                onFrameResult?.invoke(makeEmptyResult())
            }
        }
    }

    fun processFrame(imageProxy: ImageProxy) {
        val landmarker = handLandmarker ?: run { imageProxy.close(); return }

        try {
            val bitmap = imageProxy.toBitmap()
            val rotation = imageProxy.imageInfo.rotationDegrees

            val finalBitmap: Bitmap
            if (rotation != 0) {
                rotationMatrix.reset()
                rotationMatrix.postRotate(rotation.toFloat())
                finalBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, rotationMatrix, true)
                bitmap.recycle()
            } else {
                finalBitmap = bitmap
            }

            val mpImage = BitmapImageBuilder(finalBitmap).build()
            var timestampMs = SystemClock.uptimeMillis()
            if (timestampMs <= lastTimestampMs) timestampMs = lastTimestampMs + 1
            lastTimestampMs = timestampMs

            landmarker.detectAsync(mpImage, timestampMs)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e(TAG, "Frame error", e)
        } finally {
            imageProxy.close()
        }
    }

    @Suppress("DEPRECATION")
    private fun findPreferredHand(result: HandLandmarkerResult): Int {
        if (preferredHand == "both") return 0
        for (i in result.handednesses().indices) {
            val label = result.handednesses()[i][0].categoryName()
            if (label.equals(preferredHand, ignoreCase = true)) return i
        }
        return -1
    }

    private fun makeEmptyResult(): FrameResult {
        wasInVolumeMode = false
        return FrameResult(
            landmarks = null,
            action = GestureAction.None,
            isPinching = false,
            gestureState = gestureRecognizer.state,
            activationProgress = 0f,
            activationChanged = false,
            isActivated = gestureRecognizer.isActivated,
            controlMode = gestureRecognizer.controlMode,
            modeSwitchProgress = 0f,
            enteredVolumeMode = false,
            thumbGestureProgress = 0f,
            cursorX = 0.5f,
            cursorY = 0.5f,
            isCursorActive = false
        )
    }

    fun loadPreferences(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val pinchSens = prefs.getInt(PrefsKeys.PINCH_SENSITIVITY, PrefsKeys.DEFAULT_SENSITIVITY)
        val moveSens = prefs.getInt(PrefsKeys.MOVE_SENSITIVITY, PrefsKeys.DEFAULT_SENSITIVITY)
        gestureRecognizer.updateThresholds(
            PrefsKeys.pinchThreshold(pinchSens),
            PrefsKeys.moveThreshold(moveSens)
        )
        preferredHand = prefs.getString(PrefsKeys.MAIN_HAND, PrefsKeys.DEFAULT_MAIN_HAND)
            ?: PrefsKeys.DEFAULT_MAIN_HAND

        val savedMode = prefs.getString(PrefsKeys.CONTROL_MODE, "MEDIA") ?: "MEDIA"
        gestureRecognizer.setMode(
            when (savedMode) {
                "CONTROL", "SCREEN", "NAVIGATION" -> ControlMode.CONTROL
                else -> ControlMode.MEDIA
            }
        )
    }

    fun release() {
        handLandmarker?.close()
        handLandmarker = null
    }
}
