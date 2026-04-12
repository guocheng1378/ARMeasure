package com.armeasure.app

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class MeasureOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var points: List<PointF> = emptyList()
    var lines: List<Pair<PointF, PointF>> = emptyList()
    var areaPoints: List<PointF> = emptyList()
    var showLineLabels: Boolean = false
    var sweepMode: Boolean = false
    var sweepDistanceCm: Float = -1f
    var sweepHistory: List<Pair<Float, Float>> = emptyList()

    var onTap: ((Float, Float) -> Unit)? = null
    var onSweepMove: ((Float, Float) -> Unit)? = null

    private val dotP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#00FF88"); style = Paint.Style.FILL }
    private val dotRingP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3f }
    private val lineP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#00FF88"); style = Paint.Style.STROKE; strokeWidth = 4f; pathEffect = DashPathEffect(floatArrayOf(20f, 10f), 0f) }
    private val areaFillP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#226699FF"); style = Paint.Style.FILL }
    private val txtP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 40f; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); setShadowLayer(6f, 2f, 2f, Color.BLACK) }
    private val txtBgP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#CC000000"); style = Paint.Style.FILL }
    private val crossP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#80FFFFFF"); style = Paint.Style.STROKE; strokeWidth = 2f }
    private val sweepCP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFCC00"); style = Paint.Style.STROKE; strokeWidth = 3f }
    private val sweepTxtP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 56f; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); setShadowLayer(8f, 2f, 2f, Color.BLACK) }
    private val sweepTxtBgP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#DD000000"); style = Paint.Style.FILL }
    private val sweepTrailP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#66FFCC00"); style = Paint.Style.STROKE; strokeWidth = 3f }
    private val sweepDotP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFCC00"); style = Paint.Style.FILL }

    private val rRect = Rect()
    private val rPath = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (sweepMode) { drawSweep(canvas); return }

        if (areaPoints.size >= 3) {
            rPath.reset(); rPath.moveTo(areaPoints[0].x, areaPoints[0].y)
            for (i in 1 until areaPoints.size) rPath.lineTo(areaPoints[i].x, areaPoints[i].y)
            rPath.close(); canvas.drawPath(rPath, areaFillP)
        }
        for ((p1, p2) in lines) {
            canvas.drawLine(p1.x, p1.y, p2.x, p2.y, lineP)
            if (showLineLabels) drawLabel(canvas, "↔", (p1.x + p2.x) / 2, (p1.y + p2.y) / 2 - 30)
        }
        for (p in points) { canvas.drawCircle(p.x, p.y, 12f, dotP); canvas.drawCircle(p.x, p.y, 18f, dotRingP) }
        val cx = width / 2f; val cy = height / 2f
        canvas.drawLine(cx - 30, cy, cx + 30, cy, crossP)
        canvas.drawLine(cx, cy - 30, cx, cy + 30, crossP)
    }

    private fun drawSweep(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        if (sweepHistory.size >= 2) {
            val tp = Path()
            val maxD = sweepHistory.maxOfOrNull { it.second } ?: 200f
            val minD = sweepHistory.minOfOrNull { it.second } ?: 0f
            val range = (maxD - minD).coerceAtLeast(50f)
            val cBot = height - 60f; val cTop = height - 200f
            for (i in sweepHistory.indices) {
                val (sx, dist) = sweepHistory[i]
                val py = cBot - ((dist - minD) / range) * (cBot - cTop)
                if (i == 0) tp.moveTo(sx, py) else tp.lineTo(sx, py)
            }
            canvas.drawPath(tp, sweepTrailP)
        }
        canvas.drawLine(cx - 40, cy, cx + 40, cy, sweepCP)
        canvas.drawLine(cx, cy - 40, cx, cy + 40, sweepCP)
        canvas.drawCircle(cx, cy, 8f, sweepDotP)
        if (sweepDistanceCm > 0) {
            val txt = String.format("%.1f cm", sweepDistanceCm)
            sweepTxtP.getTextBounds(txt, 0, txt.length, rRect)
            val pad = 20f; val w = rRect.width() / 2f + pad; val h = rRect.height() / 2f + pad
            canvas.drawRoundRect(cx - w, cy - 70 - h, cx + w, cy - 70 + h, 16f, 16f, sweepTxtBgP)
            canvas.drawText(txt, cx - rRect.width() / 2f, cy - 70 + rRect.height() / 2f, sweepTxtP)
        }
    }

    private fun drawLabel(canvas: Canvas, text: String, x: Float, y: Float) {
        txtP.getTextBounds(text, 0, text.length, rRect)
        val pad = 16f
        canvas.drawRoundRect(
            x - rRect.width() / 2 - pad, y - rRect.height() - pad,
            x + rRect.width() / 2 + pad, y + pad, 12f, 12f, txtBgP
        )
        canvas.drawText(text, x - rRect.width() / 2f, y, txtP)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> { if (sweepMode) onSweepMove?.invoke(event.x, event.y) else onTap?.invoke(event.x, event.y); return true }
            MotionEvent.ACTION_MOVE -> { if (sweepMode) { onSweepMove?.invoke(event.x, event.y); return true } }
        }
        return super.onTouchEvent(event)
    }
}
