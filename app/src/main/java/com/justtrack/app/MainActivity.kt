package com.justtrack.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.justtrack.app.databinding.ActivityMainBinding
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val KEY_TRACKING = "isTracking"
    }

    private lateinit var binding: ActivityMainBinding
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private lateinit var engine: HandTrackingEngine
    private lateinit var volumeController: VolumeController
    private lateinit var screenController: ScreenController
    private var isTracking = false

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
        screenController = ScreenController(this)
        engine = HandTrackingEngine(this)

        isTracking = savedInstanceState?.getBoolean(KEY_TRACKING, false)
            ?: getSharedPreferences(PrefsKeys.PREFS_NAME, MODE_PRIVATE)
                .getBoolean(PrefsKeys.SERVICE_RUNNING, false)

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
        syncTrackingState()
        if (isTracking) startCamera()
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
        engine.release()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_TRACKING, isTracking)
    }

    private fun syncTrackingState() {
        val serviceRunning = getSharedPreferences(PrefsKeys.PREFS_NAME, MODE_PRIVATE)
            .getBoolean(PrefsKeys.SERVICE_RUNNING, false)
        if (isTracking && !serviceRunning) {
            isTracking = false
            stopCamera()
        }
    }

    private fun sendServiceCommand(action: String) {
        startService(Intent(this, GestureService::class.java).apply { this.action = action })
    }

    private fun loadPreferences() {
        engine.loadPreferences(this)
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val haptic = prefs.getBoolean(PrefsKeys.HAPTIC_FEEDBACK, PrefsKeys.DEFAULT_HAPTIC)
        volumeController.hapticEnabled = haptic
        screenController.hapticEnabled = haptic
    }

    private fun saveControlMode(mode: ControlMode) {
        PreferenceManager.getDefaultSharedPreferences(this)
            .edit()
            .putString(PrefsKeys.CONTROL_MODE, mode.name)
            .apply()
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
                val result = engine.processFrame(imageProxy) ?: return@setAnalyzer

                if (result.activationChanged && result.isActivated) {
                    volumeController.vibrateActivation()
                }

                if (result.enteredVolumeMode && result.controlMode == ControlMode.MEDIA) {
                    volumeController.beginSmoothVolume()
                }

                when (val action = result.action) {
                    is GestureAction.ModeSwitch -> {
                        saveControlMode(result.controlMode)
                        runOnUiThread {
                            val msg = if (result.controlMode == ControlMode.SCREEN)
                                getString(R.string.toast_mode_screen)
                            else getString(R.string.toast_mode_media)
                            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                        }
                    }
                    is GestureAction.VolumeSet,
                    is GestureAction.PlayPause,
                    is GestureAction.NextTrack,
                    is GestureAction.PrevTrack -> volumeController.dispatchAction(action)

                    is GestureAction.SwipeUp,
                    is GestureAction.SwipeDown,
                    is GestureAction.SwipeLeft,
                    is GestureAction.SwipeRight,
                    is GestureAction.TapScreen,
                    is GestureAction.DoubleTapScreen -> screenController.dispatchAction(action)

                    is GestureAction.None -> {}
                }

                runOnUiThread {
                    binding.handOverlay.updateLandmarks(
                        result.landmarks, result.isPinching,
                        result.gestureState, result.activationProgress,
                        result.controlMode, result.modeSwitchProgress
                    )
                    binding.handOverlay.showAction(result.action)
                }
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
        getSharedPreferences(PrefsKeys.PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(PrefsKeys.SERVICE_RUNNING, running)
            .apply()
    }

    private fun updateUI() {
        binding.btnToggle.text = getString(if (isTracking) R.string.btn_stop else R.string.btn_start)
        binding.tvStatus.text = getString(if (isTracking) R.string.status_active else R.string.status_stopped)
        binding.tvStatus.setTextColor(
            ContextCompat.getColor(this, if (isTracking) R.color.status_active else R.color.status_stopped)
        )
    }
}
