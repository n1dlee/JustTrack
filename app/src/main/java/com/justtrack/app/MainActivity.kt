package com.justtrack.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.justtrack.app.databinding.ActivityMainBinding
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        const val PREF_SERVICE_RUNNING = "service_running"
    }

    private lateinit var binding: ActivityMainBinding
    private var handLandmarker: HandLandmarker? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val gestureRecognizer = PinchVolumeGesture()
    private lateinit var volumeController: VolumeController
    private var isTracking = false
    private var frameCount = 0
    private var wasActivated = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] == true
        val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions[Manifest.permission.POST_NOTIFICATIONS] == true
        } else true

        if (cameraGranted && notifGranted) {
            toggleTracking()
        } else {
            Toast.makeText(this, R.string.permissions_required, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        volumeController = VolumeController(this)
        setupHandLandmarker()
        loadPreferences()
        updateUI()

        binding.btnToggle.setOnClickListener {
            if (hasPermissions()) {
                toggleTracking()
            } else {
                requestPermissions()
            }
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onStart() {
        super.onStart()
        if (isTracking) {
            sendServiceCommand(GestureService.ACTION_STOP_CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        loadPreferences()
        if (isTracking) {
            startCamera()
        }
        updateUI()
    }

    override fun onStop() {
        super.onStop()
        if (isTracking) {
            stopCamera()
            sendServiceCommand(GestureService.ACTION_START_CAMERA)
        }
    }

    override fun onDestroy() {
        cameraExecutor.shutdown()
        handLandmarker?.close()
        super.onDestroy()
    }

    private fun sendServiceCommand(action: String) {
        val intent = Intent(this, GestureService::class.java)
        intent.action = action
        startService(intent)
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

    private fun setupHandLandmarker() {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("hand_landmarker.task")
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
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e(TAG, "Failed to init HandLandmarker", e)
            Toast.makeText(this, "Failed to load hand recognition model", Toast.LENGTH_LONG).show()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        android.util.Size(640, 480),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
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
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.e(TAG, "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProviderFuture.get().unbindAll()
        }, ContextCompat.getMainExecutor(this))
        binding.handOverlay.clear()
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

        var bitmap: android.graphics.Bitmap? = null
        var rotatedBitmap: android.graphics.Bitmap? = null
        try {
            bitmap = imageProxy.toBitmap()
            val rotation = imageProxy.imageInfo.rotationDegrees
            rotatedBitmap = if (rotation != 0) {
                val matrix = android.graphics.Matrix()
                matrix.postRotate(rotation.toFloat())
                android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            } else {
                bitmap
            }
            val mpImage = BitmapImageBuilder(rotatedBitmap).build()
            val result = handLandmarker!!.detect(mpImage)

            if (result.landmarks().isNotEmpty()) {
                val landmarks = result.landmarks()[0]
                val action = gestureRecognizer.process(landmarks)

                val nowActivated = gestureRecognizer.isActivated
                if (nowActivated && !wasActivated) {
                    volumeController.vibrateActivation()
                }
                wasActivated = nowActivated

                val thumbTip = landmarks[4]
                val indexTip = landmarks[8]
                val dx = thumbTip.x() - indexTip.x()
                val dy = thumbTip.y() - indexTip.y()
                val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                val pinching = dist < PinchVolumeGesture.DEFAULT_PINCH_THRESHOLD

                when (action) {
                    GestureAction.VOLUME_UP -> volumeController.volumeUp()
                    GestureAction.VOLUME_DOWN -> volumeController.volumeDown()
                    GestureAction.PLAY_PAUSE -> volumeController.playPause()
                    GestureAction.NEXT_TRACK -> volumeController.nextTrack()
                    GestureAction.PREV_TRACK -> volumeController.prevTrack()
                    GestureAction.NONE -> {}
                }

                val vol = volumeController.getCurrentVolume().toFloat() / volumeController.getMaxVolume().toFloat()
                val currentState = gestureRecognizer.state
                val progress = gestureRecognizer.activationProgress
                val displayAction = action

                runOnUiThread {
                    binding.handOverlay.updateLandmarks(landmarks, pinching, vol, currentState, progress)
                    binding.handOverlay.showAction(displayAction)
                }
            } else {
                gestureRecognizer.process(emptyList())
                runOnUiThread {
                    binding.handOverlay.clear()
                }
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e(TAG, "Frame processing error", e)
        } finally {
            if (rotatedBitmap != null && rotatedBitmap != bitmap) rotatedBitmap.recycle()
            bitmap?.recycle()
            imageProxy.close()
        }
    }

    private fun hasPermissions(): Boolean {
        val camera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
        } else true
        return camera && notif
    }

    private fun requestPermissions() {
        val perms = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun toggleTracking() {
        if (isTracking) {
            isTracking = false
            stopCamera()
            stopService(Intent(this, GestureService::class.java))
            setServiceRunning(false)
        } else {
            isTracking = true
            binding.previewView.post { startCamera() }
            ContextCompat.startForegroundService(this, Intent(this, GestureService::class.java))
            setServiceRunning(true)
        }
        updateUI()
    }

    private fun setServiceRunning(running: Boolean) {
        getSharedPreferences("justtrack", MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_SERVICE_RUNNING, running)
            .apply()
    }

    private fun updateUI() {
        val running = isTracking
        binding.btnToggle.text = getString(if (running) R.string.btn_stop else R.string.btn_start)
        binding.tvStatus.text = getString(if (running) R.string.status_active else R.string.status_stopped)
        binding.tvStatus.setTextColor(
            ContextCompat.getColor(this, if (running) R.color.status_active else R.color.status_stopped)
        )
    }
}
