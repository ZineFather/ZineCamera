package com.zinecamera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class ZoomFramingMiniMap @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xDDEAF2FF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(1.35f)
    }
    private val cropShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC10151D.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(4.2f)
    }
    private val cropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFD54F.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(1.8f)
    }
    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(1.25f)
    }
    private val labelBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAA080C13.toInt()
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = dp(9.5f)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    @Volatile private var zoomFactor = 1f
    @Volatile private var centerXFraction = 0.5f
    @Volatile private var centerYFraction = 0.5f

    fun setZoom(zoom: Float, nativeMax: Float, centerX: Float = 0.5f, centerY: Float = 0.5f) {
        zoomFactor = max(1f, zoom)
        centerXFraction = centerX.coerceIn(0f, 1f)
        centerYFraction = centerY.coerceIn(0f, 1f)
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= dp(12f) || h <= dp(24f)) return

        val edge = dp(2f)
        val outer = RectF(edge, edge, w - edge, h - edge)
        canvas.drawRoundRect(outer, dp(11f), dp(11f), borderPaint)

        val left = dp(8f)
        val top = dp(8f)
        val right = w - dp(8f)
        val bottom = h - dp(23f)
        val zoomRatio = (1f / zoomFactor).coerceIn(0.018f, 1f)
        val frameWidth = (right - left) * zoomRatio
        val frameHeight = (bottom - top) * zoomRatio
        val centerX = left + (right - left) * centerXFraction
        val centerY = top + (bottom - top) * centerYFraction
        val frame = RectF(
            centerX - frameWidth * 0.5f,
            centerY - frameHeight * 0.5f,
            centerX + frameWidth * 0.5f,
            centerY + frameHeight * 0.5f
        )

        canvas.drawRoundRect(frame, dp(2f), dp(2f), cropShadowPaint)
        canvas.drawRoundRect(frame, dp(2f), dp(2f), cropPaint)
        val cross = dp(4.5f)
        canvas.drawLine(centerX - cross, centerY, centerX + cross, centerY, crosshairPaint)
        canvas.drawLine(centerX, centerY - cross, centerX, centerY + cross, crosshairPaint)

        val label = "WIDE  ${String.format(java.util.Locale.US, "%.1fx", zoomFactor)}"
        val textWidth = labelPaint.measureText(label)
        val labelRect = RectF(dp(5f), h - dp(19f), dp(12f) + textWidth, h - dp(4f))
        canvas.drawRoundRect(labelRect, dp(5f), dp(5f), labelBackgroundPaint)
        canvas.drawText(label, dp(8f), h - dp(8f), labelPaint)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
