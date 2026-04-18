package com.armeasure.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Apple Measure-style overlay.
 * P0: thin lines, small arrows, small dots, extension lines, smaller label
 * P1: live preview line during placement
 */
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

    var lineDistanceLabels: List<String> = emptyList()

    // Placement state
    var placingSecondPoint: Boolean = false
    /** Live crosshair position during placement (set by MainActivity) */
    var liveCrosshair: PointF? = null

    var onTap: ((Float, Float) -> Unit)? = null
    var onMove: ((Float, Float) -> Unit)? = null
    var onSweepMove: ((Float, Float) -> Unit)? = null

    // ── Apple-dimension-line paint palette ──

    // Main line: thin white solid
    private val lineP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
        strokeCap = Paint.Cap.ROUND
    }
    // Line glow: very subtle
    private val lineGlowP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#30FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
        maskFilter = BlurMaskFilter(4f, BlurMaskFilter.Blur.NORMAL)
    }
    // Extension lines: thin perpendicular lines from endpoints
    private val extLineP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#60FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    // Endpoint dot: small white filled
    private val dotP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    // Endpoint ring: thin outer ring
    private val dotRingP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    // Pulse ring
    private val pulseRingP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#60FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    // Arrow head
    private val arrowP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    // Live preview line (first point → crosshair)
    private val previewP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#50FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }
    // Distance label text: smaller, medium weight
    private val txtP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 26f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    // Label background capsule
    private val txtBgP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B3000000")  // 70% opacity
        style = Paint.Style.FILL
    }
    // Capsule border
    private val txtBorderP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#30FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    // Center crosshair
    private val crossP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#60FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    // Placement crosshair
    private val placeCrossP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    // Area fill
    private val areaFillP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#226699FF")
        style = Paint.Style.FILL
    }
    // Sweep paints (unchanged)
    private val sweepCP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFCC00"); style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val sweepTxtP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 56f; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setShadowLayer(8f, 2f, 2f, Color.BLACK)
    }
    private val sweepTxtBgP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#DD000000"); style = Paint.Style.FILL
    }
    private val sweepTrailP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66FFCC00"); style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val sweepDotP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFCC00"); style = Paint.Style.FILL
    }

    private val rRect = Rect()
    private val rPath = Path()
    private val arrowPath = Path()
    private val extPath = Path()

    // ── Pulse animation ──
    private var pulseRadius = 0f
    private var pulseAlpha = 0
    private var pulseCenter: PointF? = null
    private val pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 500
        interpolator = DecelerateInterpolator(2f)
        addUpdateListener {
            val frac = it.animatedFraction
            pulseRadius = 12f + frac * 30f
            pulseAlpha = ((1f - frac) * 80).toInt()
            invalidate()
        }
    }

    fun triggerPulse(x: Float, y: Float) {
        pulseCenter = PointF(x, y)
        pulseAnimator.cancel()
        pulseAnimator.start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (sweepMode) { drawSweep(canvas); return }

        // Area fill
        if (areaPoints.size >= 3) {
            rPath.reset()
            rPath.moveTo(areaPoints[0].x, areaPoints[0].y)
            for (i in 1 until areaPoints.size) rPath.lineTo(areaPoints[i].x, areaPoints[i].y)
            rPath.close()
            canvas.drawPath(rPath, areaFillP)
        }

        // Lines: glow → solid → extension lines → arrows
        for (i in lines.indices) {
            val (p1, p2) = lines[i]
            canvas.drawLine(p1.x, p1.y, p2.x, p2.y, lineGlowP)
            canvas.drawLine(p1.x, p1.y, p2.x, p2.y, lineP)
            drawExtensionLines(canvas, p1.x, p1.y, p2.x, p2.y)
            drawArrowHead(canvas, p1.x, p1.y, p2.x, p2.y)
            drawArrowHead(canvas, p2.x, p2.y, p1.x, p1.y)
        }

        // Live preview line (first point → crosshair)
        if (placingSecondPoint && points.size == 1 && liveCrosshair != null) {
            val p1 = points[0]
            val hp = liveCrosshair!!
            canvas.drawLine(p1.x, p1.y, hp.x, hp.y, previewP)
        }

        // Labels
        for (i in lines.indices) {
            if (i < lineDistanceLabels.size) {
                val (p1, p2) = lines[i]
                drawAppleLabel(canvas, lineDistanceLabels[i], (p1.x + p2.x) / 2f, (p1.y + p2.y) / 2f)
            }
        }

        // Endpoint dots: small white + thin ring
        for (p in points) {
            canvas.drawCircle(p.x, p.y, 4f, dotP)
            canvas.drawCircle(p.x, p.y, 10f, dotRingP)
        }

        // Pulse
        pulseCenter?.let { pc ->
            if (pulseAnimator.isRunning) {
                pulseRingP.alpha = pulseAlpha
                canvas.drawCircle(pc.x, pc.y, pulseRadius, pulseRingP)
            }
        }

        // Center crosshair
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawLine(cx - 20, cy, cx + 20, cy, crossP)
        canvas.drawLine(cx, cy - 20, cx, cy + 20, crossP)

        // First point crosshair (placing state)
        if (placingSecondPoint && points.size == 1) {
            val px = points[0].x
            val py = points[0].y
            canvas.drawLine(px - 24, py, px + 24, py, placeCrossP)
            canvas.drawLine(px, py - 24, px, py + 24, placeCrossP)
        }
    }

    /**
     * Extension lines: short perpendicular lines at endpoints (Apple dimension-line style).
     */
    private fun drawExtensionLines(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) {
        val dx = x2 - x1
        val dy = y2 - y1
        val len = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        if (len < 1f) return
        val nx = -dy / len
        val ny = dx / len
        val ext = 12f  // extension line half-length

        // Perpendicular extension at each endpoint
        canvas.drawLine(x1 + nx * ext, y1 + ny * ext, x1 - nx * ext, y1 - ny * ext, extLineP)
        canvas.drawLine(x2 + nx * ext, y2 + ny * ext, x2 - nx * ext, y2 - ny * ext, extLineP)
    }

    /**
     * Small arrow head at (fromX, fromY) pointing toward (toX, toY).
     */
    private fun drawArrowHead(canvas: Canvas, fromX: Float, fromY: Float, toX: Float, toY: Float) {
        val dx = toX - fromX
        val dy = toY - fromY
        val len = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        if (len < 1f) return
        val ux = dx / len
        val uy = dy / len
        val px = -uy
        val py = ux

        val aLen = 8f   // arrow length (was 14)
        val aWid = 4f    // half-width (was 7)

        val baseX = fromX + ux * aLen
        val baseY = fromY + uy * aLen
        val lx = baseX + px * aWid
        val ly = baseY + py * aWid
        val rx = baseX - px * aWid
        val ry = baseY - py * aWid

        arrowPath.reset()
        arrowPath.moveTo(fromX, fromY)
        arrowPath.lineTo(lx, ly)
        arrowPath.lineTo(rx, ry)
        arrowPath.close()
        canvas.drawPath(arrowPath, arrowP)
    }

    /**
     * Apple-style capsule label: small text, 25dp above line midpoint.
     */
    private fun drawAppleLabel(canvas: Canvas, text: String, x: Float, y: Float) {
        txtP.getTextBounds(text, 0, text.length, rRect)
        val padH = 14f
        val padV = 8f
        val w = rRect.width() / 2f + padH
        val h = rRect.height() / 2f + padV

        // 25dp above the line midpoint
        val labelY = y - 25f

        val rect = RectF(x - w, labelY - h, x + w, labelY + h)
        canvas.drawRoundRect(rect, h, h, txtBgP)
        canvas.drawRoundRect(rect, h, h, txtBorderP)

        val textY = labelY + rRect.height() / 2f
        canvas.drawText(text, x, textY, txtP)
    }

    private fun drawSweep(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        if (sweepHistory.size >= 2) {
            rPath.reset()
            val maxD = sweepHistory.maxOfOrNull { it.second } ?: 200f
            val minD = sweepHistory.minOfOrNull { it.second } ?: 0f
            val range = (maxD - minD).coerceAtLeast(50f)
            val cBot = height - 60f
            val cTop = height - 200f
            for (i in sweepHistory.indices) {
                val (sx, dist) = sweepHistory[i]
                val py = cBot - ((dist - minD) / range) * (cBot - cTop)
                if (i == 0) rPath.moveTo(sx, py) else rPath.lineTo(sx, py)
            }
            canvas.drawPath(rPath, sweepTrailP)
        }
        canvas.drawLine(cx - 40, cy, cx + 40, cy, sweepCP)
        canvas.drawLine(cx, cy - 40, cx, cy + 40, sweepCP)
        canvas.drawCircle(cx, cy, 8f, sweepDotP)
        if (sweepDistanceCm > 0) {
            val txt = String.format("%.1f cm", sweepDistanceCm)
            sweepTxtP.getTextBounds(txt, 0, txt.length, rRect)
            val pad = 20f
            val w = rRect.width() / 2f + pad
            val h = rRect.height() / 2f + pad
            canvas.drawRoundRect(cx - w, cy - 70 - h, cx + w, cy - 70 + h, 16f, 16f, sweepTxtBgP)
            canvas.drawText(txt, cx - rRect.width() / 2f, cy - 70 + rRect.height() / 2f, sweepTxtP)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (sweepMode) onSweepMove?.invoke(event.x, event.y)
                else onTap?.invoke(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (sweepMode) {
                    onSweepMove?.invoke(event.x, event.y)
                    return true
                }
                // P1: track finger for live preview line
                onMove?.invoke(event.x, event.y)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
