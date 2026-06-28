package com.justtrack.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.preference.PreferenceManager
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.concurrent.Executors

class GestureService : Service(), LifecycleOwner {

    companion object {
        private const val TAG = "GestureService"
        private const val CHANNEL_ID = "justtrack_channel"
        private const val NOTIFICATION_ID = 1
        private const val MODEL_ASSET = "hand_landmarker.task"
        const val ACTION_START_CAMERA = "com.justtrack.app.START_CAMERA"
        const val ACTION_STOP_CAMERA = "com.justtrack.app.STOP_CAMERA"
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private var handLandmarker: HandLandmarker? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val gestureRecognizer = PinchVolumeGesture()
    private lateinit var volumeController: VolumeController
    private var frameCount = 0
    private var cameraRunning = false
    private var wasActivated = false

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        volumeController = VolumeController(this)
        loadPreferences()
        createNotificationChannel()
        setupHandLandmarker()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CAMERA -> {
                if (!cameraRunning) startCamera()
            }
            ACTION_STOP_CAMERA -> {
                stopCamera()
            }
            else -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                lifecycleRegistry.currentState = Lifecycle.State.STARTED
                lifecycleRegistry.currentState = Lifecycle.State.RESUMED
                setServiceRunning(true)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        setServiceRunning(false)
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        handLandmarker?.close()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    private fun loadPreferences() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val pinchSens = prefs.getInt("pref_pinch_sensitivity", 5)
        val moveSens = prefs.getInt("pref_move_sensitivity", 5)
        val haptic = prefs.getBoolean("pref_haptic_feedback", true)

        val pinchThreshold = 0.01f + pinchSens * 0.01f
        val moveThreshold = 0.09f - moveSens * 0.008f

        gestureRecognizer.updateThresholds(pinchThreshold, moveThreshold)
        volumeController.hapticEnabled = haptic
    }

    private fun setServiceRunning(running: Boolean) {
        getSharedPreferences("justtrack", Context.MODE_PRIVATE)
            .edit()
            .putBoolean(MainActivity.PREF_SERVICE_RUNNING, running)
            .apply()
    }

    private fun setupHandLandmarker() {
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

            handLandmarker = HandLandmarker.createFromOptions(this, options)
            if (BuildConfig.DEBUG) Log.d(TAG, "HandLandmarker initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init HandLandmarker", e)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val resolutionSelector = androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                .setResolutionStrategy(
                    androidx.camera.core.resolutionselector.ResolutionStrategy(
                        android.util.Size(640, 480),
                        androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                    )
                ).build()

            val imageAnalysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                processFrame(imageProxy)
            }

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build()

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalysis)
                cameraRunning = true
                if (BuildConfig.DEBUG) Log.d(TAG, "Service camera started")
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProviderFuture.get().unbindAll()
            cameraRunning = false
            if (BuildConfig.DEBUG) Log.d(TAG, "Service camera stopped")
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processFrame(imageProxy: ImageProxy) {
        frameCount++
        if (frameCount % 2 != 0) {
            imageProxy.close()
            return
        }

        if (handLandmarker == null) {
            imageProxy.close()
            return
        }

        var bitmap: Bitmap? = null
        var rotatedBitmap: Bitmap? = null
        try {
            bitmap = imageProxy.toBitmap()
            val rotation = imageProxy.imageInfo.rotationDegrees
            rotatedBitmap = if (rotation != 0) {
                val matrix = android.graphics.Matrix()
                matrix.postRotate(rotation.toFloat())
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            } else {
                bitmap
            }
            val mpImage = BitmapImageBuilder(rotatedBitmap).build()
            val result: HandLandmarkerResult = handLandmarker!!.detect(mpImage)

            if (result.landmarks().isNotEmpty()) {
                val landmarks = result.landmarks()[0]
                val action = gestureRecognizer.process(landmarks)

                val nowActivated = gestureRecognizer.isActivated
                if (nowActivated && !wasActivated) {
                    volumeController.vibrateActivation()
                }
                wasActivated = nowActivated

                when (action) {
                    GestureAction.VOLUME_UP -> volumeController.volumeUp()
                    GestureAction.VOLUME_DOWN -> volumeController.volumeDown()
                    GestureAction.PLAY_PAUSE -> volumeController.playPause()
                    GestureAction.NEXT_TRACK -> volumeController.nextTrack()
                    GestureAction.PREV_TRACK -> volumeController.prevTrack()
                    GestureAction.NONE -> {}
                }
            } else {
                gestureRecognizer.process(emptyList())
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e(TAG, "Frame processing error", e)
        } finally {
            if (rotatedBitmap != null && rotatedBitmap != bitmap) rotatedBitmap.recycle()
            bitmap?.recycle()
            imageProxy.close()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_desc)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
