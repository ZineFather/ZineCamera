package com.zinecamera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.TextureView
import android.view.View
import android.os.Handler
import android.os.Looper
import kotlin.math.max

/**
 * UI-only zoom framing guide. It samples the already-rendered main TextureView;
 * it never creates, owns, or changes a Camera2 Surface or capture session.
 */
class ZoomGuideMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var sourceTextureView: TextureView? = null
    private var previewBitmap: Bitmap? = null
    private var zoomRatio = 1f
    private var panX = 0f
    private var panY = 0f
    private var captureRunning = false

    private val density = resources.displayMetrics.density
    private val cornerRadius = 12f * density
    private val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(20, 20, 20)
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.35f * density
    }
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFD54F.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 1.35f * density
    }
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55000000
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 10f * density
        setShadowLayer(2f * density, 0f, 1f * density, Color.BLACK)
    }
    private val clipPath = Path()
    private val bounds = RectF()
    private val imageBounds = RectF()
    private val frameBounds = RectF()

    private val captureRunnable = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow || zoomRatio < MIN_ZOOM) {
                captureRunning = false
                return
            }
            captureSourceFrame()
            mainHandler.postDelayed(this, CAPTURE_INTERVAL_MS)
        }
    }

    init {
        setWillNotDraw(false)
        visibility = GONE
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setSourceTextureView(source: TextureView) {
        if (sourceTextureView === source) return
        sourceTextureView = source
        if (zoomRatio >= MIN_ZOOM && isAttachedToWindow) startCaptureLoop()
    }

    fun setZoomRatio(value: Float, horizontalPan: Float = panX, verticalPan: Float = panY) {
        zoomRatio = if (value.isFinite()) value.coerceAtLeast(1f) else 1f
        panX = horizontalPan.coerceIn(-1f, 1f)
        panY = verticalPan.coerceIn(-1f, 1f)
        val shouldShow = zoomRatio >= MIN_ZOOM
        if (shouldShow) {
            if (visibility != VISIBLE) visibility = VISIBLE
            if (isAttachedToWindow) startCaptureLoop()
        } else {
            visibility = GONE
            stopCaptureLoop()
            clearPreviewBitmap()
        }
        invalidate()
    }

    private fun startCaptureLoop() {
        if (captureRunning || zoomRatio < MIN_ZOOM || sourceTextureView == null) return
        captureRunning = true
        mainHandler.removeCallbacks(captureRunnable)
        mainHandler.post(captureRunnable)
    }

    private fun stopCaptureLoop() {
        captureRunning = false
        mainHandler.removeCallbacks(captureRunnable)
    }

    private fun captureSourceFrame() {
        val source = sourceTextureView ?: return
        if (!source.isAvailable || source.width <= 0 || source.height <= 0) return
        val captured = try {
            source.getBitmap(BITMAP_WIDTH, BITMAP_HEIGHT)
        } catch (_: Throwable) {
            null
        } ?: return
        val previous = previewBitmap
        previewBitmap = captured
        if (previous !== captured && previous?.isRecycled == false) {
            runCatching { previous.recycle() }
        }
        invalidate()
    }

    private fun clearPreviewBitmap() {
        val old = previewBitmap
        previewBitmap = null
        if (old?.isRecycled == false) runCatching { old.recycle() }
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (zoomRatio >= MIN_ZOOM) startCaptureLoop()
    }

    override fun onDetachedFromWindow() {
        stopCaptureLoop()
        clearPreviewBitmap()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0 || zoomRatio < MIN_ZOOM) return

        bounds.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, outerPaint)

        val inset = 2f * density
        imageBounds.set(inset, inset, bounds.right - inset, bounds.bottom - inset)
        clipPath.reset()
        clipPath.addRoundRect(imageBounds, cornerRadius - inset, cornerRadius - inset, Path.Direction.CW)
        val save = canvas.save()
        canvas.clipPath(clipPath)

        val bitmap = previewBitmap
        if (bitmap != null && !bitmap.isRecycled) {
            val scale = max(imageBounds.width() / bitmap.width, imageBounds.height() / bitmap.height)
            val drawWidth = bitmap.width * scale
            val drawHeight = bitmap.height * scale
            val left = imageBounds.centerX() - drawWidth / 2f
            val top = imageBounds.centerY() - drawHeight / 2f
            canvas.drawBitmap(bitmap, null, RectF(left, top, left + drawWidth, top + drawHeight), imagePaint)
        } else {
            canvas.drawRect(imageBounds, shadePaint)
        }

        val fraction = (1f / zoomRatio).coerceIn(0.035f, 0.2f)
        val frameWidth = (imageBounds.width() * fraction).coerceAtLeast(10f * density)
        val frameHeight = (imageBounds.height() * fraction).coerceAtLeast(10f * density)
        val centerX = imageBounds.centerX() + panX * (imageBounds.width() - frameWidth) / 2f
        val centerY = imageBounds.centerY() + panY * (imageBounds.height() - frameHeight) / 2f
        frameBounds.set(
            centerX - frameWidth / 2f,
            centerY - frameHeight / 2f,
            centerX + frameWidth / 2f,
            centerY + frameHeight / 2f
        )
        frameBounds.intersect(imageBounds)
        canvas.drawRect(frameBounds, framePaint)

        val crossSize = 5f * density
        canvas.drawLine(centerX - crossSize, centerY, centerX + crossSize, centerY, crosshairPaint)
        canvas.drawLine(centerX, centerY - crossSize, centerX, centerY + crossSize, crosshairPaint)
        canvas.drawText(String.format(java.util.Locale.US, "%.1fx", zoomRatio), imageBounds.left + 6f * density, imageBounds.top + 13f * density, labelPaint)
        canvas.restoreToCount(save)
        canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, borderPaint)
    }

    companion object {
        private const val MIN_ZOOM = 5f
        private const val BITMAP_WIDTH = 160
        private const val BITMAP_HEIGHT = 120
        private const val CAPTURE_INTERVAL_MS = 180L
    }
}
