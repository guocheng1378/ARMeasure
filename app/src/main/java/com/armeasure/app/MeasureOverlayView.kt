package com.armeasure.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Apple Measure-style overlay with:
 * - Solid lines with subtle glow (not dashed)
 * - White dot endpoints with animated rings
 * - Floating distance label in capsule above line midpoint
 * - Animated crosshair when placing points
 * - Perspective-aware rendering hints
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

    // Apple-style: line distance labels (one per line, in display text)
    var lineDistanceLabels: List<String> = emptyList()

    // Placement state for animated feedback
    var placingSecondPoint: Boolean = false
    var placementCrosshair: PointF? = null  // live crosshair position during placement

    var onTap: ((Float, Float) -> Unit)? = null
    var onSweepMove: ((Float, Float) -> Unit)? = null

    // ── Apple-style paint palette ──

    // Line: white-ish with subtle green tint, solid
    private val lineP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        strokeCap = Paint.Cap.ROUND
    }
    // Line glow: behind the main line
    private val lineGlowP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#40FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 12f
        strokeCap = Paint.Cap.ROUND
        maskFilter = BlurMaskFilter(8f, BlurMaskFilter.Blur.NORMAL)
    }
    // Endpoint dot: white filled
    private val dotP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    // Endpoint ring: subtle outer ring
    private val dotRingP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    // Animated pulse ring (expanding on tap)
    private val pulseRingP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#60FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    // Distance label text
    private val txtP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 38f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    // Distance label background capsule
    private val txtBgP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC000000")
        style = Paint.Style.FILL
    }
    // Capsule border
    private val txtBorderP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#40FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    // Crosshair (center + when placing)
    private val crossP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    // Arrow head: filled triangle at line endpoints
    private val arrowP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    // Placement crosshair: brighter, animated
    private val placeCrossP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    // Area fill
    private val areaFillP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#226699FF")
        style = Paint.Style.FILL
    }
    // Sweep paints
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

    // ── Pulse animation state ──
    private var pulseRadius = 0f
    private var pulseAlpha = 0
    private var pulseCenter: PointF? = null
    private val pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 600
        interpolator = DecelerateInterpolator(2f)
        addUpdateListener {
            val frac = it.animatedFraction
            pulseRadius = 18f + frac * 40f
            pulseAlpha = ((1f - frac) * 100).toInt()
            invalidate()
        }
    }

    /** Trigger the expanding ring pulse at the given point */
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

        // Lines — Apple style: glow + solid + arrows at both ends
        for (i in lines.indices) {
            val (p1, p2) = lines[i]
            // Glow layer
            canvas.drawLine(p1.x, p1.y, p2.x, p2.y, lineGlowP)
            // Main solid line
            canvas.drawLine(p1.x, p1.y, p2.x, p2.y, lineP)
            // Arrow heads pointing inward (Apple dimension-line style)
            drawArrowHead(canvas, p1.x, p1.y, p2.x, p2.y)
            drawArrowHead(canvas, p2.x, p2.y, p1.x, p1.y)
        }

        // Distance labels above lines (Apple-style capsule)
        for (i in lines.indices) {
            if (i < lineDistanceLabels.size) {
                val (p1, p2) = lines[i]
                drawAppleLabel(canvas, lineDistanceLabels[i], (p1.x + p2.x) / 2f, (p1.y + p2.y) / 2f)
            }
        }

        // Endpoint dots — Apple style: white fill + ring
        for (p in points) {
            canvas.drawCircle(p.x, p.y, 8f, dotP)
            canvas.drawCircle(p.x, p.y, 16f, dotRingP)
        }

        // Pulse animation
        pulseCenter?.let { pc ->
            if (pulseAnimator.isRunning) {
                pulseRingP.alpha = pulseAlpha
                canvas.drawCircle(pc.x, pc.y, pulseRadius, pulseRingP)
            }
        }

        // Center crosshair (always visible)
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawLine(cx - 24, cy, cx + 24, cy, crossP)
        canvas.drawLine(cx, cy - 24, cx, cy + 24, crossP)

        // Placement crosshair (when waiting for second point)
        if (placingSecondPoint && points.size == 1) {
            val px = points[0].x
            val py = points[0].y
            // Draw a brighter, larger crosshair at the first point
            canvas.drawLine(px - 30, py, px + 30, py, placeCrossP)
            canvas.drawLine(px, py - 30, px, py + 30, placeCrossP)
            canvas.drawCircle(px, py, 5f, dotP)
        }
    }

    /**
     * Draw an arrow head at (fromX, fromY) pointing toward (toX, toY).
     * Apple-style: small filled triangle, ~14px long.
     */
    private fun drawArrowHead(canvas: Canvas, fromX: Float, fromY: Float, toX: Float, toY: Float) {
        val dx = toX - fromX
        val dy = toY - fromY
        val len = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        if (len < 1f) return

        // Unit vector along line direction
        val ux = dx / len
        val uy = dy / len
        // Perpendicular vector
        val px = -uy
        val py = ux

        val arrowLen = 14f   // arrow length along line
        val arrowWid = 7f    // half-width perpendicular

        // Tip is at the endpoint
        val tipX = fromX
        val tipY = fromY
        // Base center (pulled back along line)
        val baseX = fromX + ux * arrowLen
        val baseY = fromY + uy * arrowLen
        // Left and right base corners
        val lx = baseX + px * arrowWid
        val ly = baseY + py * arrowWid
        val rx = baseX - px * arrowWid
        val ry = baseY - py * arrowWid

        arrowPath.reset()
        arrowPath.moveTo(tipX, tipY)
        arrowPath.lineTo(lx, ly)
        arrowPath.lineTo(rx, ry)
        arrowPath.close()
        canvas.drawPath(arrowPath, arrowP)
    }

    /**
     * Apple-style distance label: rounded capsule with text, floating above the line.
     */
    private fun drawAppleLabel(canvas: Canvas, text: String, x: Float, y: Float) {
        // Measure text for capsule sizing
        txtP.getTextBounds(text, 0, text.length, rRect)
        val padH = 18f
        val padV = 12f
        val w = rRect.width() / 2f + padH
        val h = rRect.height() / 2f + padV

        // Float above the line midpoint
        val labelY = y - 36f

        // Background capsule
        val rect = RectF(x - w, labelY - h, x + w, labelY + h)
        canvas.drawRoundRect(rect, h, h, txtBgP)
        // Subtle border
        canvas.drawRoundRect(rect, h, h, txtBorderP)

        // Text (vertically centered)
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
            }
        }
        return super.onTouchEvent(event)
    }
}
