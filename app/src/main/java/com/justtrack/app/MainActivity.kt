package com.justtrack.app

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.justtrack.app.databinding.ActivityMainBinding

/**
 * Shows the camera preview, status and setup. It owns no camera or gesture state:
 * [GestureService] does, and this activity only binds to it while visible.
 */
class MainActivity : AppCompatActivity(), GestureService.Listener {

    private lateinit var binding: ActivityMainBinding
    private var service: GestureService? = null
    private var status = TrackingStatus.STOPPED
    private var currentMode = ControlMode.MEDIA

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val connected = (binder as GestureService.LocalBinder).service
            service = connected
            connected.addListener(this@MainActivity)
            connected.attachPreview(binding.previewView.surfaceProvider)
            connected.reloadPreferences()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            onStatusChanged(TrackingStatus.STOPPED)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Only the camera is required. Notifications are optional: without them the
        // foreground service still runs, its notification is just not shown.
        if (hasCameraPermission()) {
            GestureService.start(this)
        } else {
            Toast.makeText(this, R.string.camera_permission_required, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        binding.previewView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateOverlayContentRect() }
        binding.handOverlay.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateOverlayContentRect() }

        currentMode = savedControlMode()

        binding.btnToggle.setOnClickListener { onToggleClicked() }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnAccessibility.setOnClickListener { showAccessibilityDisclosure() }
        binding.btnOverlay.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri())
            )
        }

        updateUI()
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, GestureService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        currentMode = savedControlMode()
        service?.reloadPreferences()
        updateUI()
    }

    override fun onStop() {
        service?.let {
            it.detachPreview(binding.previewView.surfaceProvider)
            it.removeListener(this)
        }
        unbindService(connection)
        service = null
        binding.handOverlay.clear()
        super.onStop()
    }

    // ---------------------------------------------------------------------
    // GestureService.Listener (main thread)
    // ---------------------------------------------------------------------

    override fun onStatusChanged(status: TrackingStatus) {
        this.status = status
        if (!status.isActive) binding.handOverlay.clear()
        updateUI()
    }

    override fun onFrame(result: HandTrackingEngine.FrameResult) {
        binding.handOverlay.update(result)
        if (result.controlMode != currentMode) {
            currentMode = result.controlMode
            updateUI()
        }
    }

    // ---------------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------------

    private fun onToggleClicked() {
        if (status.isActive) {
            GestureService.stop(this)
            return
        }
        val needed = mutableListOf<String>()
        if (!hasCameraPermission()) needed += Manifest.permission.CAMERA
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isEmpty()) {
            GestureService.start(this)
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    /**
     * Prominent disclosure required before asking the user to enable an
     * AccessibilityService that is not an assistive-technology tool.
     */
    private fun showAccessibilityDisclosure() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.a11y_disclosure_title)
            .setMessage(R.string.a11y_disclosure_message)
            .setPositiveButton(R.string.a11y_disclosure_accept) { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton(R.string.a11y_disclosure_decline, null)
            .show()
    }

    // ---------------------------------------------------------------------
    // UI
    // ---------------------------------------------------------------------

    private fun updateUI() {
        binding.btnToggle.text = getString(if (status.isActive) R.string.btn_stop else R.string.btn_start)

        val modeLabel = getString(
            when (currentMode) {
                ControlMode.MEDIA -> R.string.mode_label_media
                ControlMode.CONTROL -> R.string.mode_label_control
            }
        )
        val (text, color) = when (status.phase) {
            TrackingStatus.Phase.STOPPED -> getString(R.string.status_stopped) to R.color.status_stopped
            TrackingStatus.Phase.STARTING -> getString(R.string.status_starting) to R.color.status_stopped
            TrackingStatus.Phase.RUNNING -> getString(R.string.status_running, modeLabel) to R.color.status_active
            TrackingStatus.Phase.ERROR -> getString(errorText(status.error)) to R.color.status_error
        }
        binding.tvStatus.text = text
        binding.tvStatus.setTextColor(ContextCompat.getColor(this, color))

        binding.rowAccessibility.isVisible = !isAccessibilityServiceEnabled()
        binding.rowOverlay.isVisible = !Settings.canDrawOverlays(this)
    }

    private fun errorText(error: TrackingStatus.Error?): Int = when (error) {
        TrackingStatus.Error.CAMERA_PERMISSION -> R.string.status_error_camera_permission
        TrackingStatus.Error.MODEL_UNAVAILABLE -> R.string.status_error_model
        TrackingStatus.Error.CAMERA_UNAVAILABLE -> R.string.status_error_camera
        TrackingStatus.Error.NO_FRAMES -> R.string.status_error_no_frames
        TrackingStatus.Error.FOREGROUND_NOT_ALLOWED -> R.string.status_error_foreground
        null -> R.string.status_stopped
    }

    private fun updateOverlayContentRect() {
        val preview = binding.previewView
        val overlay = binding.handOverlay
        if (preview.width == 0 || overlay.width == 0) return
        val previewPos = IntArray(2)
        val overlayPos = IntArray(2)
        preview.getLocationInWindow(previewPos)
        overlay.getLocationInWindow(overlayPos)
        val left = (previewPos[0] - overlayPos[0]).toFloat()
        val top = (previewPos[1] - overlayPos[1]).toFloat()
        val rect = RectF(left, top, left + preview.width, top + preview.height)
        if (rect != overlay.contentRect) overlay.contentRect = rect
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun isAccessibilityServiceEnabled(): Boolean {
        val manager = getSystemService(AccessibilityManager::class.java) ?: return false
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            val info = it.resolveInfo.serviceInfo
            info.packageName == packageName && info.name == SwipeAccessibilityService::class.java.name
        }
    }

    private fun savedControlMode(): ControlMode {
        val saved = PreferenceManager.getDefaultSharedPreferences(this)
            .getString(PrefsKeys.CONTROL_MODE, ControlMode.MEDIA.name)
        return if (saved in setOf("CONTROL", "SCREEN", "NAVIGATION")) ControlMode.CONTROL else ControlMode.MEDIA
    }
}
