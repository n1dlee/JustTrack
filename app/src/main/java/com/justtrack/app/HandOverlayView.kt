package com.justtrack.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.withClip
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

class HandOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val THUMB_TIP = 4
        private const val INDEX_TIP = 8
        private const val ACTION_DISPLAY_MS = 1200L

        private val CONNECTIONS = listOf(
            0 to 1, 1 to 2, 2 to 3, 3 to 4,
            0 to 5, 5 to 6, 6 to 7, 7 to 8,
            0 to 9, 9 to 10, 10 to 11, 11 to 12,
            0 to 13, 13 to 14, 14 to 15, 15 to 16,
            0 to 17, 17 to 18, 18 to 19, 19 to 20,
            5 to 9, 9 to 13, 13 to 17
        )
    }

    private var landmarks: List<NormalizedLandmark>? = null
    private var isPinching = false
    private var gestureState = PinchVolumeGesture.State.INACTIVE
    private var activationProgress = 0f
    private var currentMode = ControlMode.MEDIA
    private var modeSwitchProgress = 0f
    private var thumbGestureProgress = 0f
    private var cursorX = 0.5f
    private var cursorY = 0.5f
    private var isCursorActive = false
    private var isPointing = false
    private val displaySize = android.graphics.Point()
    private var drawOriginX = 0f
    private var drawOriginY = 0f
    private var imageWidth = 0
    private var imageHeight = 0
    private var lastAction: GestureAction = GestureAction.None
    private var lastActionShowTime = 0L
    private val screenLocation = IntArray(2)
    private val contentBounds = RectF()

    /**
     * Where the camera image is shown, in this view's coordinates. Landmarks are
     * mapped into it with the same center-crop as PreviewView's fillCenter.
     * Null means the whole view.
     */
    var contentRect: RectF? = null
        set(value) {
            field = value
            invalidate()
        }

    /** The "show your palm" prompt; off for the system-wide overlay over other apps. */
    var showIdleHint = true

    /**
     * Draw the hand in screen space with the same mapping as the cursor, instead of
     * over a camera preview. Used by the full-screen overlay over other apps, so the
     * cursor circle sits exactly on the drawn index fingertip.
     */
    var landmarksInScreenSpace = false

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val pinchDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        style = Paint.Style.FILL
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val pinchLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val inactiveDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 255, 255, 255)
        style = Paint.Style.FILL
    }

    private val inactiveLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(40, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255)
        textSize = 40f
        textAlign = Paint.Align.CENTER
    }

    private val hintSmallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 255, 255, 255)
        textSize = 28f
        textAlign = Paint.Align.CENTER
    }

    private val activationHintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 100, 255, 100)
        textSize = 28f
        textAlign = Paint.Align.CENTER
    }

    private val progressArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
    }

    private val progressBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }

    private val progressTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 32f
        textAlign = Paint.Align.CENTER
    }

    private val borderOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 0, 200, 0)
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val borderInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 0, 200, 0)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val controlBorderOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 0, 120, 255)
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val controlBorderInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 0, 120, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val actionBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }

    private val actionIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 64f
        textAlign = Paint.Align.CENTER
    }

    private val actionLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 30f
        textAlign = Paint.Align.CENTER
    }

    private val activeBadgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 0, 180, 0)
        style = Paint.Style.FILL
    }

    private val controlBadgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 0, 100, 220)
        style = Paint.Style.FILL
    }

    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24f
        textAlign = Paint.Align.CENTER
    }

    private val modeSwitchArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 100, 180, 255)
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
    }

    private val thumbArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 180, 50)
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
    }

    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 80, 80)
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val cursorFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 255, 80, 80)
        style = Paint.Style.FILL
    }

    private val cursorDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 80, 80)
        style = Paint.Style.FILL
    }

    private val cursorParkedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(170, 255, 80, 80)
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }

    fun update(result: HandTrackingEngine.FrameResult) {
        landmarks = result.landmarks
        isPinching = result.isPinching
        gestureState = result.gestureState
        activationProgress = result.activationProgress
        currentMode = result.controlMode
        modeSwitchProgress = result.modeSwitchProgress
        thumbGestureProgress = result.thumbGestureProgress
        cursorX = result.cursorX
        cursorY = result.cursorY
        isCursorActive = result.isCursorActive
        isPointing = result.isPointing
        imageWidth = result.imageWidth
        imageHeight = result.imageHeight
        showAction(result.action)
        invalidate()
    }

    fun showAction(action: GestureAction) {
        if (action is GestureAction.None || action is GestureAction.VolumeSet ||
            action is GestureAction.ScreenScroll || action is GestureAction.CursorMove) return
        lastAction = action
        lastActionShowTime = SystemClock.uptimeMillis()
        invalidate()
    }

    fun clear() {
        landmarks = null
        isPinching = false
        gestureState = PinchVolumeGesture.State.INACTIVE
        activationProgress = 0f
        modeSwitchProgress = 0f
        thumbGestureProgress = 0f
        isCursorActive = false
        isPointing = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val content = contentRect ?: contentBounds.apply { set(0f, 0f, width.toFloat(), height.toFloat()) }
        val w = content.width()
        val h = content.height()
        val isActive = gestureState != PinchVolumeGesture.State.INACTIVE &&
                gestureState != PinchVolumeGesture.State.ACTIVATING

        ScreenMapper.displaySize(context).let { displaySize.set(it.x, it.y) }
        getLocationOnScreen(screenLocation)
        drawOriginX = screenLocation[0] + content.left
        drawOriginY = screenLocation[1] + content.top

        canvas.withClip(content) {
            translate(content.left, content.top)
            drawCameraArea(this, w, h, isActive)
        }

        // The cursor is drawn in screen space, outside the camera area if needed,
        // exactly where an Accessibility tap would land.
        drawCursor(canvas)
    }

    private fun drawCameraArea(canvas: Canvas, w: Float, h: Float, isActive: Boolean) {
        if (isActive) drawActiveBorder(canvas, w, h)

        val lm = landmarks
        if (lm == null || lm.size < 21) {
            if (!isActive && showIdleHint) drawHint(canvas, w, h)
        } else {
            when (gestureState) {
                PinchVolumeGesture.State.INACTIVE -> {
                    drawLandmarksDimmed(canvas, lm, w, h)
                    if (showIdleHint) drawHint(canvas, w, h)
                }
                PinchVolumeGesture.State.ACTIVATING -> {
                    drawLandmarksDimmed(canvas, lm, w, h)
                    drawActivationProgress(canvas, w, h)
                }
                PinchVolumeGesture.State.MODE_SWITCHING -> {
                    drawLandmarks(canvas, lm, w, h)
                    drawModeSwitchProgress(canvas, w, h)
                    drawActiveBadge(canvas, w)
                }
                PinchVolumeGesture.State.THUMB_GESTURE -> {
                    drawLandmarks(canvas, lm, w, h)
                    drawThumbGestureProgress(canvas, w, h)
                    drawActiveBadge(canvas, w)
                }
                else -> {
                    drawLandmarks(canvas, lm, w, h)
                    drawActiveBadge(canvas, w)
                }
            }
        }

        drawActionFeedback(canvas, w, h)
    }

    private fun drawLandmarks(canvas: Canvas, lm: List<NormalizedLandmark>, w: Float, h: Float) {
        for ((from, to) in CONNECTIONS) {
            val x1 = mapX(lm[from].x(), w, h)
            val y1 = mapY(lm[from].y(), w, h)
            val x2 = mapX(lm[to].x(), w, h)
            val y2 = mapY(lm[to].y(), w, h)
            val isPinchConnection = (from == THUMB_TIP || to == THUMB_TIP) ||
                    (from == INDEX_TIP || to == INDEX_TIP)
            canvas.drawLine(x1, y1, x2, y2, if (isPinchConnection && isPinching) pinchLinePaint else linePaint)
        }

        for (i in lm.indices) {
            val x = mapX(lm[i].x(), w, h)
            val y = mapY(lm[i].y(), w, h)
            val isPinchDot = (i == THUMB_TIP || i == INDEX_TIP) && isPinching
            val radius = if (isPinchDot) 12f else 6f
            canvas.drawCircle(x, y, radius, if (isPinchDot) pinchDotPaint else dotPaint)
        }
    }

    private fun drawLandmarksDimmed(canvas: Canvas, lm: List<NormalizedLandmark>, w: Float, h: Float) {
        for ((from, to) in CONNECTIONS) {
            val x1 = mapX(lm[from].x(), w, h)
            val y1 = mapY(lm[from].y(), w, h)
            val x2 = mapX(lm[to].x(), w, h)
            val y2 = mapY(lm[to].y(), w, h)
            canvas.drawLine(x1, y1, x2, y2, inactiveLinePaint)
        }
        for (i in lm.indices) {
            val x = mapX(lm[i].x(), w, h)
            val y = mapY(lm[i].y(), w, h)
            canvas.drawCircle(x, y, 4f, inactiveDotPaint)
        }
    }

    private fun drawHint(canvas: Canvas, w: Float, h: Float) {
        canvas.drawText("✋  Покажи ладонь", w / 2f, h * 0.88f, hintPaint)
        canvas.drawText("Держи 1.5 сек для активации", w / 2f, h * 0.93f, hintSmallPaint)
    }

    private fun drawActivationProgress(canvas: Canvas, w: Float, h: Float) {
        val centerX = w / 2f
        val centerY = h / 2f
        val radius = 70f
        val rect = RectF(centerX - radius, centerY - radius, centerX + radius, centerY + radius)

        canvas.drawArc(rect, 0f, 360f, false, progressBgPaint)
        canvas.drawArc(rect, -90f, 360f * activationProgress, false, progressArcPaint)

        val percent = (activationProgress * 100).toInt()
        canvas.drawText("$percent%", centerX, centerY + 12f, progressTextPaint)
        canvas.drawText("Держи...", centerX, centerY + radius + 40f, activationHintPaint)
    }

    private fun drawModeSwitchProgress(canvas: Canvas, w: Float, h: Float) {
        val centerX = w / 2f
        val centerY = h / 2f
        val radius = 60f
        val rect = RectF(centerX - radius, centerY - radius, centerX + radius, centerY + radius)

        canvas.drawArc(rect, 0f, 360f, false, progressBgPaint)
        canvas.drawArc(rect, -90f, 360f * modeSwitchProgress, false, modeSwitchArcPaint)

        canvas.drawText("✌️", centerX, centerY + 14f, progressTextPaint)
        val targetMode = when (currentMode) {
            ControlMode.MEDIA -> "Управление"
            ControlMode.CONTROL -> "Медиа"
        }
        canvas.drawText("→ $targetMode", centerX, centerY + radius + 40f, activationHintPaint)
    }

    private fun drawThumbGestureProgress(canvas: Canvas, w: Float, h: Float) {
        val centerX = w / 2f
        val centerY = h / 2f
        val radius = 60f
        val rect = RectF(centerX - radius, centerY - radius, centerX + radius, centerY + radius)

        canvas.drawArc(rect, 0f, 360f, false, progressBgPaint)
        canvas.drawArc(rect, -90f, 360f * thumbGestureProgress, false, thumbArcPaint)

        canvas.drawText("👍", centerX, centerY + 14f, progressTextPaint)
    }

    private fun drawActiveBorder(canvas: Canvas, w: Float, h: Float) {
        when (currentMode) {
            ControlMode.CONTROL -> {
                canvas.drawRect(0f, 0f, w, h, controlBorderOuterPaint)
                canvas.drawRect(4f, 4f, w - 4f, h - 4f, controlBorderInnerPaint)
            }
            ControlMode.MEDIA -> {
                canvas.drawRect(0f, 0f, w, h, borderOuterPaint)
                canvas.drawRect(4f, 4f, w - 4f, h - 4f, borderInnerPaint)
            }
        }
    }

    private fun drawActiveBadge(canvas: Canvas, w: Float) {
        val badgeW = if (currentMode == ControlMode.CONTROL) 180f else 140f
        val badgeH = 32f
        val x = w / 2f - badgeW / 2f
        val y = 16f

        when (currentMode) {
            ControlMode.CONTROL -> {
                canvas.drawRoundRect(x, y, x + badgeW, y + badgeH, 16f, 16f, controlBadgePaint)
                canvas.drawText("🎮 Управление", w / 2f, y + 24f, badgeTextPaint)
            }
            ControlMode.MEDIA -> {
                canvas.drawRoundRect(x, y, x + badgeW, y + badgeH, 16f, 16f, activeBadgePaint)
                canvas.drawText("🎵 Медиа", w / 2f, y + 24f, badgeTextPaint)
            }
        }
    }

    /**
     * A circle at the cursor: filled while the index finger is steering it, dashed
     * once it is parked and waiting for a tap. Drawn in screen space, exactly where
     * the Accessibility tap lands.
     */
    private fun drawCursor(canvas: Canvas) {
        if (!isCursorActive) return

        val target = ScreenMapper.toScreen(displaySize.x, displaySize.y, cursorX, cursorY)
        val cx = target.x - screenLocation[0]
        val cy = target.y - screenLocation[1]
        val radius = 22f

        if (isPointing) {
            canvas.drawCircle(cx, cy, radius, cursorFillPaint)
            canvas.drawCircle(cx, cy, radius, cursorPaint)
            canvas.drawCircle(cx, cy, 4f, cursorDotPaint)
        } else {
            canvas.drawCircle(cx, cy, radius, cursorParkedPaint)
            canvas.drawCircle(cx, cy, 4f, cursorDotPaint)
        }
    }

    private fun drawActionFeedback(canvas: Canvas, w: Float, h: Float) {
        if (lastAction is GestureAction.None) return
        val elapsed = SystemClock.uptimeMillis() - lastActionShowTime
        if (elapsed > ACTION_DISPLAY_MS) {
            lastAction = GestureAction.None
            return
        }

        val fadeRatio = 1f - (elapsed.toFloat() / ACTION_DISPLAY_MS)
        val alpha = (fadeRatio * 255).toInt().coerceIn(0, 255)
        val scale = 0.8f + fadeRatio * 0.2f

        val (icon, label) = when (lastAction) {
            is GestureAction.PlayPause -> "⏯" to "Play / Pause"
            is GestureAction.NextTrack -> "⏭" to "Next Track"
            is GestureAction.PrevTrack -> "⏮" to "Prev Track"
            is GestureAction.SwipeLeft -> "⬅" to "Свайп влево"
            is GestureAction.SwipeRight -> "➡" to "Свайп вправо"
            is GestureAction.TapScreen -> "👆" to "Тап"
            is GestureAction.TapAt -> "🎯" to "Тап"
            is GestureAction.GoBack -> "⬅" to "Назад"
            is GestureAction.GoHome -> "🏠" to "Домой"
            is GestureAction.RecentApps -> "📋" to "Приложения"
            is GestureAction.PreviousApp -> "🔄" to "Предыдущее"
            is GestureAction.ModeSwitch -> "🔄" to "Смена режима"
            else -> return
        }

        val cx = w / 2f
        val cy = h * 0.45f
        val bgRadius = 72f * scale

        actionBgPaint.alpha = (alpha * 0.5f).toInt()
        canvas.drawCircle(cx, cy, bgRadius, actionBgPaint)

        actionIconPaint.alpha = alpha
        actionIconPaint.textSize = 52f * scale
        canvas.drawText(icon, cx, cy + 8f, actionIconPaint)

        actionLabelPaint.alpha = alpha
        actionLabelPaint.textSize = 26f
        canvas.drawText(label, cx, cy + bgRadius + 30f, actionLabelPaint)

        postInvalidateDelayed(16)
    }

    private fun mirrorX(x: Float): Float = 1f - x

    /** Scale of the camera image inside a w x h area with center-crop, or -1 if unknown. */
    private fun imageScale(w: Float, h: Float): Float {
        if (imageWidth <= 0 || imageHeight <= 0) return -1f
        return maxOf(w / imageWidth, h / imageHeight)
    }

    private fun mapX(normalizedX: Float, w: Float, h: Float): Float {
        if (landmarksInScreenSpace) {
            return CursorMapping.screenX(normalizedX, clamp = false) * (displaySize.x - 1) - drawOriginX
        }
        val scale = imageScale(w, h)
        if (scale < 0f) return mirrorX(normalizedX) * w
        val shownWidth = imageWidth * scale
        return (w - shownWidth) / 2f + mirrorX(normalizedX) * shownWidth
    }

    private fun mapY(normalizedY: Float, w: Float, h: Float): Float {
        if (landmarksInScreenSpace) {
            return CursorMapping.screenY(normalizedY, clamp = false) * (displaySize.y - 1) - drawOriginY
        }
        val scale = imageScale(w, h)
        if (scale < 0f) return normalizedY * h
        val shownHeight = imageHeight * scale
        return (h - shownHeight) / 2f + normalizedY * shownHeight
    }
}
