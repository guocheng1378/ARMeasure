package com.armeasure.app

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Overlay view that draws measurement markers, lines, and labels on top of the camera preview.
 */
class MeasureOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // Data set by Activity
    var points: List<PointF> = emptyList()
    var lines: List<Pair<PointF, PointF>> = emptyList()
    var areaPoints: List<PointF> = emptyList()
    var showLineLabels: Boolean = false

    // Tap callback
    var onTap: ((Float, Float) -> Unit)? = null

    // Paints
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00FF88")
        style = Paint.Style.FILL
    }
    private val dotRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00FF88")
        style = Paint.Style.STROKE
        strokeWidth = 4f
        pathEffect = DashPathEffect(floatArrayOf(20f, 10f), 0f)
    }
    private val areaLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6699FF")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val areaFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#226699FF")
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 40f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setShadowLayer(6f, 2f, 2f, Color.BLACK)
    }
    private val textBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC000000")
        style = Paint.Style.FILL
    }
    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    // Touch feedback
    private var touchX = -1f
    private var touchY = -1f
    private var touchAnim = 0f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw area fill
        if (areaPoints.size >= 3) {
            val path = Path()
            path.moveTo(areaPoints[0].x, areaPoints[0].y)
            for (i in 1 until areaPoints.size) {
                path.lineTo(areaPoints[i].x, areaPoints[i].y)
            }
            path.close()
            canvas.drawPath(path, areaFillPaint)
        }

        // Draw lines (green dashed)
        for ((p1, p2) in lines) {
            canvas.drawLine(p1.x, p1.y, p2.x, p2.y, linePaint)
            if (showLineLabels) {
                val midX = (p1.x + p2.x) / 2
                val midY = (p1.y + p2.y) / 2
                drawLabel(canvas, "↔", midX, midY - 30)
            }
        }

        // Draw dots
        for (p in points) {
            canvas.drawCircle(p.x, p.y, 12f, dotPaint)
            canvas.drawCircle(p.x, p.y, 18f, dotRingPaint)
        }

        // Center crosshair
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawLine(cx - 30, cy, cx + 30, cy, crosshairPaint)
        canvas.drawLine(cx, cy - 30, cx, cy + 30, crosshairPaint)
    }

    private fun drawLabel(canvas: Canvas, text: String, x: Float, y: Float) {
        val bounds = Rect()
        textPaint.getTextBounds(text, 0, text.length, bounds)
        val pad = 16f
        canvas.drawRoundRect(
            x - bounds.width() / 2 - pad,
            y - bounds.height() - pad,
            x + bounds.width() / 2 + pad,
            y + pad,
            12f, 12f,
            textBgPaint
        )
        canvas.drawText(text, x - bounds.width() / 2f, y, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            onTap?.invoke(event.x, event.y)
            return true
        }
        return super.onTouchEvent(event)
    }
}
