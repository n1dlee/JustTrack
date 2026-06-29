package com.justtrack.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageProxy
import androidx.preference.PreferenceManager
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker

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
        val enteredVolumeMode: Boolean
    )

    private var handLandmarker: HandLandmarker? = null
    val gestureRecognizer = PinchVolumeGesture()
    private var frameSkip = false
    @Volatile private var wasActivated = false
    private var wasInVolumeMode = false
    private val rotationMatrix = Matrix()
    var preferredHand: String = PrefsKeys.DEFAULT_MAIN_HAND

    val isModelLoaded: Boolean get() = handLandmarker != null

    init {
        setupHandLandmarker(context)
    }

    private fun setupHandLandmarker(context: Context) {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET)
                .build()

            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setMinHandDetectionConfidence(0.5f)
                .setMinHandPresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setNumHands(1)
                .setRunningMode(com.google.mediapipe.tasks.vision.core.RunningMode.IMAGE)
                .build()

            handLandmarker = HandLandmarker.createFromOptions(context, options)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init HandLandmarker", e)
        }
    }

    fun processFrame(imageProxy: ImageProxy): FrameResult? {
        frameSkip = !frameSkip
        if (frameSkip) {
            imageProxy.close()
            return null
        }

        val landmarker = handLandmarker
        if (landmarker == null) {
            imageProxy.close()
            return null
        }

        var bitmap: Bitmap? = null
        var rotatedBitmap: Bitmap? = null
        try {
            bitmap = imageProxy.toBitmap()
            val rotation = imageProxy.imageInfo.rotationDegrees
            rotatedBitmap = if (rotation != 0) {
                rotationMatrix.reset()
                rotationMatrix.postRotate(rotation.toFloat())
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, rotationMatrix, true)
            } else {
                bitmap
            }

            val mpImage = BitmapImageBuilder(rotatedBitmap).build()
            val result = landmarker.detect(mpImage)

            if (result.landmarks().isNotEmpty()) {
                val handIndex = findPreferredHand(result)
                if (handIndex < 0) {
                    gestureRecognizer.process(emptyList())
                    return makeEmptyResult()
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

                return FrameResult(
                    landmarks = landmarks,
                    action = action,
                    isPinching = pinching,
                    gestureState = gestureRecognizer.state,
                    activationProgress = gestureRecognizer.activationProgress,
                    activationChanged = changed,
                    isActivated = nowActivated,
                    controlMode = gestureRecognizer.controlMode,
                    modeSwitchProgress = gestureRecognizer.modeSwitchProgress,
                    enteredVolumeMode = enteredVolumeMode
                )
            } else {
                gestureRecognizer.process(emptyList())
                return makeEmptyResult()
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e(TAG, "Frame processing error", e)
            return null
        } finally {
            if (rotatedBitmap != null && rotatedBitmap !== bitmap) rotatedBitmap.recycle()
            bitmap?.recycle()
            imageProxy.close()
        }
    }

    @Suppress("DEPRECATION")
    private fun findPreferredHand(result: com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult): Int {
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
            enteredVolumeMode = false
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
            if (savedMode == "SCREEN") ControlMode.SCREEN else ControlMode.MEDIA
        )
    }

    fun release() {
        handLandmarker?.close()
        handLandmarker = null
    }
}
