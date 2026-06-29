package com.justtrack.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.preference.PreferenceManager
import java.util.concurrent.Executors

class GestureService : LifecycleService() {

    companion object {
        private const val TAG = "GestureService"
        private const val CHANNEL_ID = "justtrack_channel"
        private const val NOTIFICATION_ID = 1
        const val ACTION_START_CAMERA = "com.justtrack.app.START_CAMERA"
        const val ACTION_STOP_CAMERA = "com.justtrack.app.STOP_CAMERA"
        const val ACTION_STOP_TRACKING = "com.justtrack.app.STOP_TRACKING"
    }

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var engine: HandTrackingEngine
    private lateinit var volumeController: VolumeController
    private lateinit var screenController: ScreenController
    private var cameraRunning = false

    override fun onCreate() {
        super.onCreate()
        volumeController = VolumeController(this)
        screenController = ScreenController(this)
        engine = HandTrackingEngine(this)
        loadPreferences()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START_CAMERA -> {
                if (!cameraRunning) startCamera()
            }
            ACTION_STOP_CAMERA -> {
                stopCamera()
            }
            ACTION_STOP_TRACKING -> {
                setServiceRunning(false)
                stopSelf()
            }
            else -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                setServiceRunning(true)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        setServiceRunning(false)
        engine.release()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    private fun loadPreferences() {
        engine.loadPreferences(this)
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val haptic = prefs.getBoolean(PrefsKeys.HAPTIC_FEEDBACK, PrefsKeys.DEFAULT_HAPTIC)
        volumeController.hapticEnabled = haptic
        screenController.hapticEnabled = haptic
    }

    private fun setServiceRunning(running: Boolean) {
        getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PrefsKeys.SERVICE_RUNNING, running)
            .apply()
    }

    private fun saveControlMode(mode: ControlMode) {
        PreferenceManager.getDefaultSharedPreferences(this)
            .edit()
            .putString(PrefsKeys.CONTROL_MODE, mode.name)
            .apply()
    }

    private fun showToast(message: String) {
        mainHandler.post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

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

                if (result.activationChanged) {
                    if (result.isActivated) {
                        volumeController.vibrateActivation()
                        showToast(getString(R.string.toast_activated))
                    } else {
                        showToast(getString(R.string.toast_deactivated))
                    }
                }

                if (result.enteredVolumeMode && result.controlMode == ControlMode.MEDIA) {
                    volumeController.beginSmoothVolume()
                }

                when (val action = result.action) {
                    is GestureAction.ModeSwitch -> {
                        saveControlMode(result.controlMode)
                        val msg = if (result.controlMode == ControlMode.SCREEN)
                            getString(R.string.toast_mode_screen)
                        else getString(R.string.toast_mode_media)
                        showToast(msg)
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
            }

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build()

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalysis)
                cameraRunning = true
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.e(TAG, "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProviderFuture.get().unbindAll()
            cameraRunning = false
        }, ContextCompat.getMainExecutor(this))
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
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, GestureService::class.java).apply { action = ACTION_STOP_TRACKING },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.notif_stop), stopIntent)
            .setOngoing(true)
            .build()
    }
}
