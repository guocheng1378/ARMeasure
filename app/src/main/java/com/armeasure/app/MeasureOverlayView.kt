package com.armeasure.app

import android.animation.*
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator

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
    var placingSecondPoint: Boolean = false
    var liveCrosshair: PointF? = null
    var surfaceDetected: Boolean = false

    var onTap: ((Float, Float) -> Unit)? = null
    var onMove: ((Float, Float) -> Unit)? = null
    var onSweepMove: ((Float, Float) -> Unit)? = null
    var onScale: ((Float) -> Unit)? = null

    private val lineP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 1.8f; strokeCap = Paint.Cap.ROUND
    }
    private val lineGlowP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#30FFFFFF"); style = Paint.Style.STROKE; strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND; maskFilter = BlurMaskFilter(4f, BlurMaskFilter.Blur.NORMAL)
    }
    private val extLineP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#60FFFFFF"); style = Paint.Style.STROKE; strokeWidth = 1f
    }
    private val dotP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val dotRingP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80FFFFFF"); style = Paint.Style.STROKE; strokeWidth = 1.5f
    }
    private val pulseRingP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#60FFFFFF"); style = Paint.Style.STROKE; strokeWidth = 1.5f
    }
    private val arrowP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val previewP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#50FFFFFF"); style = Paint.Style.STROKE; strokeWidth = 1.5f
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }
    private val txtP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 26f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val txtBgP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#B3000000"); style = Paint.Style.FILL }
    private val txtBorderP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#30FFFFFF"); style = Paint.Style.STROKE; strokeWidth = 1f
    }
    private val crossP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#60FFFFFF"); style = Paint.Style.STROKE; strokeWidth = 1.5f
    }
    private val crossActiveP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f
    }
    // #17: Use green color for placement cross to differentiate from the selected point
    private val placeCrossP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00FF88"); style = Paint.Style.STROKE; strokeWidth = 1.5f
    }
    private val areaFillP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#226699FF"); style = Paint.Style.FILL }
    private val areaFillLiveP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#156699FF"); style = Paint.Style.FILL }
    private val areaLineP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#60FFFFFF"); style = Paint.Style.STROKE; strokeWidth = 1.5f
    }
    private val sweepCP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFCC00"); style = Paint.Style.STROKE; strokeWidth = 3f }
    private val sweepTxtP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 56f; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setShadowLayer(8f, 2f, 2f, Color.BLACK)
    }
    private val sweepTxtBgP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#DD000000"); style = Paint.Style.FILL }
    private val sweepTrailP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#66FFCC00"); style = Paint.Style.STROKE; strokeWidth = 3f }
    private val sweepDotP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFCC00"); style = Paint.Style.FILL }

    private val rRect = Rect()
    private val rPath = Path()
    private val arrowPath = Path()

    // ── Line expand animation (#18: add isRunning guard) ──
    var lineExpandProgress = 1f
    private val lineExpandAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 350; interpolator = DecelerateInterpolator(2.5f)
        addUpdateListener { lineExpandProgress = it.animatedValue as Float; invalidate() }
    }
    fun animateLineExpand() {
        if (lineExpandAnimator.isRunning) lineExpandAnimator.cancel()
        lineExpandProgress = 0f
        lineExpandAnimator.start()
    }

    // ── Fade out animation (#19: cancel before start) ──
    var fadeOutAlpha = 1f
    private val fadeOutAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
        duration = 200; interpolator = AccelerateInterpolator(2f)
        addUpdateListener { fadeOutAlpha = it.animatedValue as Float; invalidate() }
    }
    fun animateFadeOut(onEnd: () -> Unit) {
        if (fadeOutAnimator.isRunning) fadeOutAnimator.cancel()
        fadeOutAnimator.removeAllListeners()
        fadeOutAnimator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(a: android.animation.Animator) { fadeOutAlpha = 1f; onEnd() }
        })
        fadeOutAnimator.start()
    }

    // ── Pulse animation (#20: clear pulseCenter on end) ──
    private var pulseRadius = 0f; private var pulseAlpha = 0; private var pulseCenter: PointF? = null
    private val pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 500; interpolator = DecelerateInterpolator(2f)
        addUpdateListener {
            val f = it.animatedFraction
            pulseRadius = 12f + f * 30f
            pulseAlpha = ((1f - f) * 80).toInt()
            invalidate()
        }
    }
    fun triggerPulse(x: Float, y: Float) {
        if (pulseAnimator.isRunning) pulseAnimator.cancel()
        pulseCenter = PointF(x, y)
        pulseAnimator.removeAllListeners()
        pulseAnimator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(a: android.animation.Animator) { pulseCenter = null }
        })
        pulseAnimator.start()
    }

    // ── Mode transition animation (#22) ──
    private var activeButtonAlpha = 1f
    private val modeTransitionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 150; interpolator = DecelerateInterpolator(2f)
        addUpdateListener { activeButtonAlpha = it.animatedValue as Float; invalidate() }
    }

    // ── Area point pop-in animation (#23) ──
    private var newPointScale = 1f
    private val pointPopAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 200; interpolator = DecelerateInterpolator(2.5f)
        addUpdateListener { newPointScale = 0.3f + 0.7f * (it.animatedValue as Float); invalidate() }
    }
    fun animateNewPoint() {
        if (pointPopAnimator.isRunning) pointPopAnimator.cancel()
        pointPopAnimator.start()
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean { onScale?.invoke(d.scaleFactor); return true }
    })

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (sweepMode) { drawSweep(canvas); return }

        if (areaPoints.size >= 2) {
            rPath.reset(); rPath.moveTo(areaPoints[0].x, areaPoints[0].y)
            for (i in 1 until areaPoints.size) rPath.lineTo(areaPoints[i].x, areaPoints[i].y)
            if (areaPoints.size >= 3) rPath.close()
            canvas.drawPath(rPath, if (areaPoints.size >= 3) areaFillP else areaFillLiveP)
            for (i in 0 until areaPoints.size - 1) canvas.drawLine(areaPoints[i].x, areaPoints[i].y, areaPoints[i+1].x, areaPoints[i+1].y, areaLineP)
            if (areaPoints.size >= 3) canvas.drawLine(areaPoints.last().x, areaPoints.last().y, areaPoints[0].x, areaPoints[0].y, areaLineP)
        }

        for (i in lines.indices) {
            val (p1, p2) = lines[i]
            val mx = (p1.x + p2.x) / 2f; val my = (p1.y + p2.y) / 2f
            val ax1 = mx + (p1.x - mx) * lineExpandProgress; val ay1 = my + (p1.y - my) * lineExpandProgress
            val ax2 = mx + (p2.x - mx) * lineExpandProgress; val ay2 = my + (p2.y - my) * lineExpandProgress
            canvas.drawLine(ax1, ay1, ax2, ay2, lineGlowP)
            canvas.drawLine(ax1, ay1, ax2, ay2, lineP)
            if (lineExpandProgress > 0.3f) {
                drawExt(canvas, ax1, ay1, ax2, ay2)
                drawArrow(canvas, ax1, ay1, ax2, ay2); drawArrow(canvas, ax2, ay2, ax1, ay1)
            }
        }

        if (placingSecondPoint && points.size == 1 && liveCrosshair != null) {
            canvas.drawLine(points[0].x, points[0].y, liveCrosshair!!.x, liveCrosshair!!.y, previewP)
        }

        if (lineExpandProgress > 0.6f) {
            for (i in lines.indices) {
                if (i < lineDistanceLabels.size) {
                    val (p1, p2) = lines[i]
                    drawLabel(canvas, lineDistanceLabels[i], (p1.x + p2.x) / 2f, (p1.y + p2.y) / 2f)
                }
            }
        }

        // Draw points with pop-in animation for the last area point (#23)
        for (i in points.indices) {
            val p = points[i]
            val isLast = i == points.size - 1 && pointPopAnimator.isRunning
            if (isLast) {
                canvas.save()
                canvas.scale(newPointScale, newPointScale, p.x, p.y)
            }
            canvas.drawCircle(p.x, p.y, 4f, dotP); canvas.drawCircle(p.x, p.y, 10f, dotRingP)
            if (isLast) canvas.restore()
        }
        for (i in areaPoints.indices) {
            val p = areaPoints[i]
            val isLast = i == areaPoints.size - 1 && pointPopAnimator.isRunning
            if (isLast) {
                canvas.save()
                canvas.scale(newPointScale, newPointScale, p.x, p.y)
            }
            canvas.drawCircle(p.x, p.y, 4f, dotP); canvas.drawCircle(p.x, p.y, 10f, dotRingP)
            if (isLast) canvas.restore()
        }

        pulseCenter?.let { pc -> if (pulseAnimator.isRunning) { pulseRingP.alpha = pulseAlpha; canvas.drawCircle(pc.x, pc.y, pulseRadius, pulseRingP) } }

        // #16: Only draw crosshair when surfaceDetected (hide default crosshair otherwise)
        if (surfaceDetected) {
            val cx = width / 2f; val cy = height / 2f
            val cs = AppConstants.CROSSHAIR_SIZE
            canvas.drawLine(cx - cs, cy, cx + cs, cy, crossActiveP); canvas.drawLine(cx, cy - cs, cx, cy + cs, crossActiveP)
            canvas.drawCircle(cx, cy, 3f, dotP)
        }

        if (placingSecondPoint && points.size == 1) {
            canvas.drawLine(points[0].x - 24, points[0].y, points[0].x + 24, points[0].y, placeCrossP)
            canvas.drawLine(points[0].x, points[0].y - 24, points[0].x, points[0].y + 24, placeCrossP)
        }
    }

    private fun drawExt(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) {
        val dx = x2 - x1; val dy = y2 - y1; val len = Math.sqrt((dx*dx + dy*dy).toDouble()).toFloat()
        if (len < 1f) return; val nx = -dy / len; val ny = dx / len; val e = 12f
        c.drawLine(x1 + nx*e, y1 + ny*e, x1 - nx*e, y1 - ny*e, extLineP)
        c.drawLine(x2 + nx*e, y2 + ny*e, x2 - nx*e, y2 - ny*e, extLineP)
    }

    private fun drawArrow(c: Canvas, fx: Float, fy: Float, tx: Float, ty: Float) {
        val dx = tx - fx; val dy = ty - fy; val len = Math.sqrt((dx*dx + dy*dy).toDouble()).toFloat()
        if (len < 1f) return; val ux = dx/len; val uy = dy/len; val px = -uy; val py = ux
        val bx = fx + ux*8f; val by = fy + uy*8f
        arrowPath.reset(); arrowPath.moveTo(fx, fy); arrowPath.lineTo(bx + px*4f, by + py*4f); arrowPath.lineTo(bx - px*4f, by - py*4f); arrowPath.close()
        c.drawPath(arrowPath, arrowP)
    }

    private fun drawLabel(c: Canvas, text: String, x: Float, y: Float) {
        txtP.getTextBounds(text, 0, text.length, rRect)
        val w = rRect.width()/2f + 14f; val h = rRect.height()/2f + 8f; val ly = y - 25f
        val rect = RectF(x - w, ly - h, x + w, ly + h)
        c.drawRoundRect(rect, h, h, txtBgP); c.drawRoundRect(rect, h, h, txtBorderP)
        c.drawText(text, x, ly + rRect.height()/2f, txtP)
    }

    // #21: Sweep trail with quadratic Bezier smoothing
    private fun drawSweep(c: Canvas) {
        val cx = width/2f; val cy = height/2f
        if (sweepHistory.size >= 2) {
            rPath.reset()
            val cBot = height - 60f; val cTop = height - 200f
            val maxD = sweepHistory.maxOfOrNull { it.second } ?: 200f
            val minD = sweepHistory.minOfOrNull { it.second } ?: 0f
            val range = (maxD - minD).coerceAtLeast(50f)

            val firstX = sweepHistory[0].first
            val firstY = cBot - ((sweepHistory[0].second - minD) / range) * (cBot - cTop)
            rPath.moveTo(firstX, firstY)

            for (i in 1 until sweepHistory.size) {
                val prevX = sweepHistory[i - 1].first
                val prevY = cBot - ((sweepHistory[i - 1].second - minD) / range) * (cBot - cTop)
                val currX = sweepHistory[i].first
                val currY = cBot - ((sweepHistory[i].second - minD) / range) * (cBot - cTop)
                // Quadratic Bezier: control point is midpoint
                val cpx = (prevX + currX) / 2f
                val cpy = (prevY + currY) / 2f
                rPath.quadTo(prevX, prevY, cpx, cpy)
            }
            c.drawPath(rPath, sweepTrailP)
        }
        c.drawLine(cx-40, cy, cx+40, cy, sweepCP); c.drawLine(cx, cy-40, cx, cy+40, sweepCP); c.drawCircle(cx, cy, 8f, sweepDotP)
        if (sweepDistanceCm > 0) {
            val txt = String.format("%.1f cm", sweepDistanceCm); sweepTxtP.getTextBounds(txt, 0, txt.length, rRect)
            val pad = 20f; val w = rRect.width()/2f + pad; val h = rRect.height()/2f + pad
            c.drawRoundRect(cx-w, cy-70-h, cx+w, cy-70+h, 16f, 16f, sweepTxtBgP)
            c.drawText(txt, cx - rRect.width()/2f, cy - 70 + rRect.height()/2f, sweepTxtP)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (scaleDetector.isInProgress) return true
        when (event.action) {
            MotionEvent.ACTION_DOWN -> { if (sweepMode) onSweepMove?.invoke(event.x, event.y) else onTap?.invoke(event.x, event.y); return true }
            MotionEvent.ACTION_MOVE -> {
                if (sweepMode) { onSweepMove?.invoke(event.x, event.y); return true }
                onMove?.invoke(event.x, event.y); return true
            }
        }
        return super.onTouchEvent(event)
    }
}
