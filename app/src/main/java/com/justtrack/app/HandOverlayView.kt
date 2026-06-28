package com.justtrack.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
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
    }

    private val CONNECTIONS = listOf(
        0 to 1, 1 to 2, 2 to 3, 3 to 4,
        0 to 5, 5 to 6, 6 to 7, 7 to 8,
        0 to 9, 9 to 10, 10 to 11, 11 to 12,
        0 to 13, 13 to 14, 14 to 15, 15 to 16,
        0 to 17, 17 to 18, 18 to 19, 19 to 20,
        5 to 9, 9 to 13, 13 to 17
    )

    private var landmarks: List<NormalizedLandmark>? = null
    private var isPinching = false
    private var gestureState = PinchVolumeGesture.State.INACTIVE
    private var activationProgress = 0f
    private var lastAction: GestureAction = GestureAction.NONE
    private var lastActionShowTime = 0L

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
        textSize = 28f
        textAlign = Paint.Align.CENTER
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
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

    private val activeBadgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24f
        textAlign = Paint.Align.CENTER
    }

    fun updateLandmarks(
        newLandmarks: List<NormalizedLandmark>?,
        pinching: Boolean,
        volume: Float,
        state: PinchVolumeGesture.State = PinchVolumeGesture.State.INACTIVE,
        progress: Float = 0f
    ) {
        landmarks = newLandmarks
        isPinching = pinching
        gestureState = state
        activationProgress = progress
        invalidate()
    }

    fun showAction(action: GestureAction) {
        if (action == GestureAction.NONE) return
        lastAction = action
        lastActionShowTime = System.currentTimeMillis()
        invalidate()
    }

    fun clear() {
        landmarks = null
        isPinching = false
        gestureState = PinchVolumeGesture.State.INACTIVE
        activationProgress = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val isActive = gestureState != PinchVolumeGesture.State.INACTIVE &&
                gestureState != PinchVolumeGesture.State.ACTIVATING

        if (isActive) {
            drawActiveBorder(canvas, w, h)
        }

        val lm = landmarks
        if (lm == null || lm.size < 21) {
            if (!isActive) drawHint(canvas, w, h)
            drawActionFeedback(canvas, w, h)
            return
        }

        when (gestureState) {
            PinchVolumeGesture.State.INACTIVE -> {
                drawLandmarksDimmed(canvas, lm, w, h)
                drawHint(canvas, w, h)
            }
            PinchVolumeGesture.State.ACTIVATING -> {
                drawLandmarksDimmed(canvas, lm, w, h)
                drawActivationProgress(canvas, lm, w, h)
            }
            else -> {
                drawLandmarks(canvas, lm, w, h)
                drawActiveBadge(canvas, w)
            }
        }

        drawActionFeedback(canvas, w, h)
    }

    private fun drawLandmarks(canvas: Canvas, lm: List<NormalizedLandmark>, w: Float, h: Float) {
        for ((from, to) in CONNECTIONS) {
            val x1 = mirrorX(lm[from].x()) * w
            val y1 = lm[from].y() * h
            val x2 = mirrorX(lm[to].x()) * w
            val y2 = lm[to].y() * h
            val isPinchConnection = (from == THUMB_TIP || to == THUMB_TIP) ||
                    (from == INDEX_TIP || to == INDEX_TIP)
            canvas.drawLine(x1, y1, x2, y2, if (isPinchConnection && isPinching) pinchLinePaint else linePaint)
        }

        for (i in lm.indices) {
            val x = mirrorX(lm[i].x()) * w
            val y = lm[i].y() * h
            val isPinchDot = (i == THUMB_TIP || i == INDEX_TIP) && isPinching
            val radius = if (isPinchDot) 12f else 6f
            canvas.drawCircle(x, y, radius, if (isPinchDot) pinchDotPaint else dotPaint)
        }
    }

    private fun drawLandmarksDimmed(canvas: Canvas, lm: List<NormalizedLandmark>, w: Float, h: Float) {
        for ((from, to) in CONNECTIONS) {
            val x1 = mirrorX(lm[from].x()) * w
            val y1 = lm[from].y() * h
            val x2 = mirrorX(lm[to].x()) * w
            val y2 = lm[to].y() * h
            canvas.drawLine(x1, y1, x2, y2, inactiveLinePaint)
        }
        for (i in lm.indices) {
            val x = mirrorX(lm[i].x()) * w
            val y = lm[i].y() * h
            canvas.drawCircle(x, y, 4f, inactiveDotPaint)
        }
    }

    private fun drawHint(canvas: Canvas, w: Float, h: Float) {
        canvas.drawText("✋  Покажи ладонь", w / 2f, h * 0.88f, hintPaint)
        canvas.drawText("Держи 1.5 сек для активации", w / 2f, h * 0.93f, hintSmallPaint)
    }

    private fun drawActivationProgress(canvas: Canvas, lm: List<NormalizedLandmark>, w: Float, h: Float) {
        val centerX = w / 2f
        val centerY = h / 2f
        val radius = 70f
        val rect = RectF(centerX - radius, centerY - radius, centerX + radius, centerY + radius)

        canvas.drawArc(rect, 0f, 360f, false, progressBgPaint)
        canvas.drawArc(rect, -90f, 360f * activationProgress, false, progressArcPaint)

        val percent = (activationProgress * 100).toInt()
        progressTextPaint.textSize = 32f
        canvas.drawText("$percent%", centerX, centerY + 12f, progressTextPaint)

        hintSmallPaint.color = Color.argb(200, 100, 255, 100)
        canvas.drawText("Держи...", centerX, centerY + radius + 40f, hintSmallPaint)
        hintSmallPaint.color = Color.argb(140, 255, 255, 255)
    }

    private fun drawActiveBorder(canvas: Canvas, w: Float, h: Float) {
        borderPaint.color = Color.argb(160, 0, 200, 0)
        canvas.drawRect(0f, 0f, w, h, borderPaint)
        borderPaint.strokeWidth = 2f
        borderPaint.color = Color.argb(80, 0, 200, 0)
        canvas.drawRect(4f, 4f, w - 4f, h - 4f, borderPaint)
        borderPaint.strokeWidth = 4f
    }

    private fun drawActiveBadge(canvas: Canvas, w: Float) {
        val badgeW = 120f
        val badgeH = 32f
        val x = w / 2f - badgeW / 2f
        val y = 16f
        canvas.drawRoundRect(x, y, x + badgeW, y + badgeH, 16f, 16f, activeBadgePaint)
        canvas.drawText("● Готов", w / 2f, y + 24f, activeBadgeTextPaint)
    }

    private fun drawActionFeedback(canvas: Canvas, w: Float, h: Float) {
        if (lastAction == GestureAction.NONE) return
        val elapsed = System.currentTimeMillis() - lastActionShowTime
        if (elapsed > ACTION_DISPLAY_MS) {
            lastAction = GestureAction.NONE
            return
        }

        val fadeRatio = 1f - (elapsed.toFloat() / ACTION_DISPLAY_MS)
        val alpha = (fadeRatio * 255).toInt().coerceIn(0, 255)
        val scale = 0.8f + fadeRatio * 0.2f

        val (icon, label) = when (lastAction) {
            GestureAction.PLAY_PAUSE -> "⏯" to "Play / Pause"
            GestureAction.NEXT_TRACK -> "⏭" to "Next Track"
            GestureAction.PREV_TRACK -> "⏮" to "Prev Track"
            GestureAction.VOLUME_UP -> "🔊" to "Volume +"
            GestureAction.VOLUME_DOWN -> "🔉" to "Volume −"
            GestureAction.NONE -> return
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
}
