package com.zinecamera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import kotlin.math.max

class ZoomFramingMiniMap(context: Context) : View(context) {
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD9161A22.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8FB8FF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(1.4f)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55FFFFFF
        strokeWidth = dp(0.7f)
    }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(1.8f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = dp(10f)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }
    private var zoomFactor = 1f
    private var hardwareMax = 1f

    fun setZoom(zoom: Float, nativeMax: Float) {
        zoomFactor = max(1f, zoom)
        hardwareMax = max(1f, nativeMax)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val outer = RectF(0f, 0f, w, h)
        canvas.drawRoundRect(outer, dp(13f), dp(13f), backgroundPaint)
        canvas.save()
        canvas.clipRect(dp(7f), dp(7f), w - dp(7f), h - dp(25f))
        val left = dp(9f)
        val top = dp(9f)
        val right = w - dp(9f)
        val bottom = h - dp(27f)
        for (i in 1..3) {
            val x = left + (right - left) * i / 4f
            val y = top + (bottom - top) * i / 4f
            canvas.drawLine(x, top, x, bottom, gridPaint)
            canvas.drawLine(left, y, right, y, gridPaint)
        }
        val ratio = (1f / zoomFactor).coerceIn(0.035f, 1f)
        val fw = (right - left) * ratio
        val fh = (bottom - top) * ratio
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        val frame = RectF(cx - fw / 2f, cy - fh / 2f, cx + fw / 2f, cy + fh / 2f)
        canvas.drawRoundRect(frame, dp(2f), dp(2f), framePaint)
        canvas.drawLine(cx - dp(5f), cy, cx + dp(5f), cy, framePaint)
        canvas.drawLine(cx, cy - dp(5f), cx, cy + dp(5f), framePaint)
        canvas.restore()
        canvas.drawText("FRAMING", dp(9f), h - dp(9f), textPaint)
        canvas.drawRoundRect(outer, dp(13f), dp(13f), borderPaint)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
