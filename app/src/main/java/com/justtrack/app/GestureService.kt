package com.justtrack.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.Size
import android.view.WindowManager
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.LifecycleService
import androidx.preference.PreferenceManager
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The single owner of the camera, the hand-tracking engine and the gesture state
 * machine. The activity never binds the camera itself: while visible it binds to
 * this service, attaches its preview surface and renders the frames it receives,
 * so gesture state is continuous across foreground and background.
 *
 * The camera is only started by an explicit user action ([ACTION_START]); the
 * service is not sticky and never resumes the camera on its own.
 */
class GestureService : LifecycleService() {

    companion object {
        private const val TAG = "GestureService"
        private const val CHANNEL_ID = "justtrack_channel"
        private const val NOTIFICATION_ID = 1
        private const val WATCHDOG_INTERVAL_MS = 1000L
        private const val NO_FRAMES_TIMEOUT_MS = 4000L
        const val ACTION_START = "com.justtrack.app.START"
        const val ACTION_STOP = "com.justtrack.app.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, GestureService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, GestureService::class.java).setAction(ACTION_STOP))
        }
    }

    interface Listener {
        fun onStatusChanged(status: TrackingStatus)
        fun onFrame(result: HandTrackingEngine.FrameResult)
    }

    inner class LocalBinder : Binder() {
        val service: GestureService get() = this@GestureService
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Listener>()
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var volumeController: VolumeController
    private lateinit var screenController: ScreenController
    private var engine: HandTrackingEngine? = null

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var preview: Preview? = null
    private var previewSurface: Preview.SurfaceProvider? = null
    private var overlayView: HandOverlayView? = null
    private var tracking = false
    private var trackingStartedAt = 0L
    @Volatile private var lastFrameAt = 0L

    @Volatile var status: TrackingStatus = TrackingStatus.STOPPED
        private set

    private val watchdog = object : Runnable {
        override fun run() {
            if (!tracking) return
            val now = SystemClock.elapsedRealtime()
            val reference = if (lastFrameAt > 0) lastFrameAt else trackingStartedAt
            if (now - reference > NO_FRAMES_TIMEOUT_MS) {
                setStatus(TrackingStatus.error(TrackingStatus.Error.NO_FRAMES))
            }
            mainHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        cameraExecutor = Executors.newSingleThreadExecutor()
        volumeController = VolumeController(this)
        screenController = ScreenController(this)
        loadControllerPreferences()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> startTracking()
            ACTION_STOP -> stopTracking(TrackingStatus.STOPPED)
            else -> if (!tracking) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (tracking) stopTracking(TrackingStatus.STOPPED)
        mainHandler.removeCallbacks(watchdog)
        removeOverlay()
        engine?.release()
        engine = null
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------------
    // Public API for the bound activity (main thread only)
    // ---------------------------------------------------------------------

    fun addListener(listener: Listener) {
        listeners.add(listener)
        listener.onStatusChanged(status)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /** Shows the camera in the activity; the system overlay is hidden meanwhile. */
    fun attachPreview(surfaceProvider: Preview.SurfaceProvider) {
        previewSurface = surfaceProvider
        removeOverlay()
        if (tracking && imageAnalysis != null) bindPreview(surfaceProvider)
    }

    fun detachPreview(surfaceProvider: Preview.SurfaceProvider) {
        if (previewSurface !== surfaceProvider) return
        previewSurface = null
        preview?.let { cameraProvider?.unbind(it) }
        preview = null
        if (tracking) addOverlay()
    }

    fun reloadPreferences() {
        loadControllerPreferences()
        val current = engine ?: return
        cameraExecutor.execute { current.loadPreferences(this) }
    }

    // ---------------------------------------------------------------------
    // Tracking lifecycle
    // ---------------------------------------------------------------------

    private fun startTracking() {
        if (tracking) return

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            // startForegroundService() obliges us to call startForeground() before stopping,
            // but a camera-type foreground service cannot start without the permission.
            enterForegroundOrFail()
            stopTracking(TrackingStatus.error(TrackingStatus.Error.CAMERA_PERMISSION))
            return
        }
        if (!enterForegroundOrFail()) {
            setStatus(TrackingStatus.error(TrackingStatus.Error.FOREGROUND_NOT_ALLOWED))
            stopSelf()
            return
        }

        val currentEngine = engine ?: HandTrackingEngine(this).also { engine = it }
        if (!currentEngine.isModelLoaded) {
            stopTracking(TrackingStatus.error(TrackingStatus.Error.MODEL_UNAVAILABLE))
            return
        }
        currentEngine.loadPreferences(this)
        currentEngine.resetGestureState()
        currentEngine.onFrameResult = ::handleFrameResult
        loadControllerPreferences()

        tracking = true
        trackingStartedAt = SystemClock.elapsedRealtime()
        lastFrameAt = 0L
        setStatus(TrackingStatus.STARTING)
        bindCamera(currentEngine)
        mainHandler.removeCallbacks(watchdog)
        mainHandler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
    }

    private fun stopTracking(finalStatus: TrackingStatus) {
        tracking = false
        mainHandler.removeCallbacks(watchdog)
        imageAnalysis?.clearAnalyzer()
        cameraProvider?.unbindAll()
        imageAnalysis = null
        preview = null
        removeOverlay()
        engine?.onFrameResult = null
        engine?.resetGestureState()
        setStatus(finalStatus)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun enterForegroundOrFail(): Boolean {
        return try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            } else {
                0
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not enter foreground", e)
            false
        }
    }

    private fun bindCamera(currentEngine: HandTrackingEngine) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (!tracking) return@addListener
            val provider = try {
                future.get()
            } catch (e: Exception) {
                stopTracking(TrackingStatus.error(TrackingStatus.Error.CAMERA_UNAVAILABLE))
                return@addListener
            }
            cameraProvider = provider

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(analysisResolution())
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(cameraExecutor) { imageProxy -> currentEngine.processFrame(imageProxy) }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                imageAnalysis = analysis
            } catch (e: Exception) {
                Log.w(TAG, "Camera bind failed", e)
                stopTracking(TrackingStatus.error(TrackingStatus.Error.CAMERA_UNAVAILABLE))
                return@addListener
            }

            val surface = previewSurface
            if (surface != null) bindPreview(surface) else addOverlay()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindPreview(surfaceProvider: Preview.SurfaceProvider) {
        val provider = cameraProvider ?: return
        preview?.let { provider.unbind(it) }
        // Same 4:3 aspect as the analysis stream, so the preview shows the field of
        // view the landmarks were computed on.
        val newPreview = Preview.Builder()
            .setResolutionSelector(analysisResolution())
            .build()
        newPreview.setSurfaceProvider(surfaceProvider)
        try {
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, newPreview)
            preview = newPreview
        } catch (e: Exception) {
            Log.w(TAG, "Preview bind failed", e)
        }
    }

    private fun analysisResolution(): ResolutionSelector =
        ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
            )
            .build()

    private fun setStatus(newStatus: TrackingStatus) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { setStatus(newStatus) }
            return
        }
        if (newStatus == status) return
        status = newStatus
        listeners.forEach { it.onStatusChanged(newStatus) }
    }

    // ---------------------------------------------------------------------
    // Frame handling (MediaPipe callback thread)
    // ---------------------------------------------------------------------

    private fun handleFrameResult(result: HandTrackingEngine.FrameResult) {
        lastFrameAt = SystemClock.elapsedRealtime()
        if (status != TrackingStatus.RUNNING) {
            mainHandler.post { if (tracking) setStatus(TrackingStatus.RUNNING) }
        }

        if (result.activationChanged) {
            if (result.isActivated) {
                volumeController.vibrateActivation()
                showToast(getString(R.string.toast_activated))
            } else {
                showToast(getString(R.string.toast_deactivated))
            }
        }

        if (result.enteredVolumeMode) {
            when (result.controlMode) {
                ControlMode.MEDIA -> volumeController.beginSmoothVolume()
                ControlMode.CONTROL -> screenController.resetScroll()
            }
        }

        when (val action = result.action) {
            is GestureAction.ModeSwitch -> {
                saveControlMode(result.controlMode)
                showToast(
                    getString(
                        when (result.controlMode) {
                            ControlMode.MEDIA -> R.string.toast_mode_media
                            ControlMode.CONTROL -> R.string.toast_mode_control
                        }
                    )
                )
            }
            is GestureAction.VolumeSet,
            is GestureAction.PlayPause,
            is GestureAction.NextTrack,
            is GestureAction.PrevTrack -> volumeController.dispatchAction(action)

            is GestureAction.SwipeLeft,
            is GestureAction.SwipeRight,
            is GestureAction.TapScreen,
            is GestureAction.GoBack,
            is GestureAction.GoHome,
            is GestureAction.RecentApps,
            is GestureAction.ScreenScroll,
            is GestureAction.TapAt,
            is GestureAction.PreviousApp -> screenController.dispatchAction(action)

            is GestureAction.CursorMove,
            is GestureAction.None -> {}
        }

        mainHandler.post {
            overlayView?.update(result)
            listeners.forEach { it.onFrame(result) }
        }
    }

    // ---------------------------------------------------------------------
    // System-wide overlay (only while the activity is not showing the preview)
    // ---------------------------------------------------------------------

    private fun addOverlay() {
        if (overlayView != null || !tracking || previewSurface != null) return
        if (!Settings.canDrawOverlays(this)) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        try {
            val view = HandOverlayView(this).apply { showIdleHint = false }
            getSystemService(WindowManager::class.java).addView(view, params)
            overlayView = view
        } catch (e: Exception) {
            Log.w(TAG, "Overlay add failed", e)
        }
    }

    private fun removeOverlay() {
        val view = overlayView ?: return
        try {
            getSystemService(WindowManager::class.java).removeView(view)
        } catch (e: Exception) {
            Log.w(TAG, "Overlay remove failed", e)
        }
        overlayView = null
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun loadControllerPreferences() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val haptic = prefs.getBoolean(PrefsKeys.HAPTIC_FEEDBACK, PrefsKeys.DEFAULT_HAPTIC)
        volumeController.hapticEnabled = haptic
        screenController.hapticEnabled = haptic
    }

    private fun saveControlMode(mode: ControlMode) {
        PreferenceManager.getDefaultSharedPreferences(this).edit {
            putString(PrefsKeys.CONTROL_MODE, mode.name)
        }
    }

    private fun showToast(message: String) {
        mainHandler.post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_desc)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, GestureService::class.java).setAction(ACTION_STOP),
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
