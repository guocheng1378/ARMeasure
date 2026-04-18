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
    var showTutorial: Boolean = false
    /** Once true, the completed line is locked and ignores all updates */
    var lineConfirmed: Boolean = false
    /** Brief flash state during confirm transition (250ms) */
    var confirmFlash: Boolean = false
    /** Level mode — shows a bubble level instead of measurement overlay */
    var levelMode: Boolean = false
    var levelTiltDeg: Float = 0f  // current tilt angle in degrees

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
        color = Color.parseColor("#A0FFFFFF"); style = Paint.Style.STROKE; strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND
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
    // Sweep ruler/grid
    private val sweepGridP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(AppConstants.SWEEP_GRID_ALPHA, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 0.5f
        pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
    }
    private val sweepRulerTxtP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80FFFFFF"); textSize = 18f; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        textAlign = Paint.Align.RIGHT
    }
    // Tap ripple
    private var rippleRadius = 0f; private var rippleAlpha = 0; private var rippleCenter: PointF? = null
    private val rippleP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#40FFFFFF"); style = Paint.Style.FILL
    }
    private val rippleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 300; interpolator = DecelerateInterpolator(2f)
        addUpdateListener {
            val f = it.animatedFraction
            rippleRadius = 5f + f * 40f
            rippleAlpha = ((1f - f) * 60).toInt()
            invalidate()
        }
    }
    // Tutorial
    private val tutorialBgP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#CC000000"); style = Paint.Style.FILL }
    private val tutorialTxtP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 32f; textAlign = Paint.Align.CENTER
    }
    private val tutorialSubTxtP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AAAAAA"); textSize = 22f; textAlign = Paint.Align.CENTER
    }
    private val tutorialHandP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00FF88"); style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val tutorialDotP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00FF88"); style = Paint.Style.FILL
    }

    private val rRect = Rect()
    private val rPath = Path()
    private val arrowPath = Path()

    // Line expand animation
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

    // Fade out animation
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

    // Pulse animation
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

    // Point pop-in animation
    private var newPointScale = 1f
    private val pointPopAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 200; interpolator = DecelerateInterpolator(2.5f)
        addUpdateListener { newPointScale = 0.3f + 0.7f * (it.animatedValue as Float); invalidate() }
    }
    fun animateNewPoint() {
        if (pointPopAnimator.isRunning) pointPopAnimator.cancel()
        pointPopAnimator.start()
    }

    // Tap ripple
    fun triggerRipple(x: Float, y: Float) {
        if (rippleAnimator.isRunning) rippleAnimator.cancel()
        rippleCenter = PointF(x, y)
        rippleAnimator.removeAllListeners()
        rippleAnimator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(a: android.animation.Animator) { rippleCenter = null }
        })
        rippleAnimator.start()
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean { onScale?.invoke(d.scaleFactor); return true }
    })

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Level mode — draw bubble level overlay and skip everything else
        if (levelMode) { drawLevel(canvas); return }

        // Tutorial overlay
        if (showTutorial) { drawTutorial(canvas); return }
        if (sweepMode) { drawSweep(canvas); return }

        // ★ Confirmed line: locked, skip all preview/live drawing
        if (lineConfirmed) {
            drawCompletedMeasurement(canvas)
            return
        }

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
                // Apple-style endpoint markers on completed line
                if (lineExpandProgress > 0.8f) {
                    drawEndpointMarker(canvas, ax1, ay1, true)
                    drawEndpointMarker(canvas, ax2, ay2, true)
                }
            }
        }
        if (lineExpandProgress > 0.6f) {
            for (i in lines.indices) {
                if (i < lineDistanceLabels.size) {
                    val (p1, p2) = lines[i]
                    drawLabel(canvas, lineDistanceLabels[i], (p1.x + p2.x) / 2f, (p1.y + p2.y) / 2f)
                }
            }
        }

        // Draw points with pop-in animation
        for (i in points.indices) {
            val p = points[i]
            val isLast = i == points.size - 1 && pointPopAnimator.isRunning
            if (isLast) { canvas.save(); canvas.scale(newPointScale, newPointScale, p.x, p.y) }
            canvas.drawCircle(p.x, p.y, 4f, dotP); canvas.drawCircle(p.x, p.y, 10f, dotRingP)
            if (isLast) canvas.restore()
        }
        for (i in areaPoints.indices) {
            val p = areaPoints[i]
            val isLast = i == areaPoints.size - 1 && pointPopAnimator.isRunning
            if (isLast) { canvas.save(); canvas.scale(newPointScale, newPointScale, p.x, p.y) }
            canvas.drawCircle(p.x, p.y, 4f, dotP); canvas.drawCircle(p.x, p.y, 10f, dotRingP)
            if (isLast) canvas.restore()
        }

        pulseCenter?.let { pc -> if (pulseAnimator.isRunning) { pulseRingP.alpha = pulseAlpha; canvas.drawCircle(pc.x, pc.y, pulseRadius, pulseRingP) } }
        rippleCenter?.let { rc -> if (rippleAnimator.isRunning) { rippleP.alpha = rippleAlpha; canvas.drawCircle(rc.x, rc.y, rippleRadius, rippleP) } }

        // ── Center crosshair: always visible as aim indicator ──
        if (!lineConfirmed) {
            val cx = width / 2f; val cy = height / 2f
            val cs = AppConstants.CROSSHAIR_SIZE
            crossP.alpha = 160
            crossP.strokeWidth = 1.5f
            canvas.drawLine(cx - cs, cy, cx + cs, cy, crossP)
            canvas.drawLine(cx, cy - cs, cx, cy + cs, crossP)
            canvas.drawCircle(cx, cy, 3f, dotP)
            crossP.alpha = 255; crossP.strokeWidth = 1.5f
        }

    }

    /** Draw the locked/confirmed measurement line — no animation, no live updates. */
    private fun drawCompletedMeasurement(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        for (i in lines.indices) {
            // Clamp all coordinates to screen bounds
            val (rp1, rp2) = lines[i]
            val p1x = rp1.x.coerceIn(0f, w); val p1y = rp1.y.coerceIn(0f, h)
            val p2x = rp2.x.coerceIn(0f, w); val p2y = rp2.y.coerceIn(0f, h)
            // Animated expansion from midpoint
            val mx = (p1x + p2x) / 2f; val my = (p1y + p2y) / 2f
            val ax1 = mx + (p1x - mx) * lineExpandProgress
            val ay1 = my + (p1y - my) * lineExpandProgress
            val ax2 = mx + (p2x - mx) * lineExpandProgress
            val ay2 = my + (p2y - my) * lineExpandProgress
            // Solid thick line
            canvas.drawLine(ax1, ay1, ax2, ay2, lineGlowP)
            lineP.strokeWidth = 2.5f
            canvas.drawLine(ax1, ay1, ax2, ay2, lineP)
            lineP.strokeWidth = 1.8f // restore
            // Extension ticks + arrows
            if (lineExpandProgress > 0.3f) {
                drawExt(canvas, ax1, ay1, ax2, ay2)
                drawArrow(canvas, ax1, ay1, ax2, ay2)
                drawArrow(canvas, ax2, ay2, ax1, ay1)
                // Apple-style endpoint markers on completed line
                if (lineExpandProgress > 0.8f) {
                    drawEndpointMarker(canvas, ax1, ay1, true)
                    drawEndpointMarker(canvas, ax2, ay2, true)
                }
            }
        }
        // Distance label(s)
        if (showLineLabels && lineExpandProgress > 0.6f) {
            for (i in lines.indices) {
                if (i < lineDistanceLabels.size) {
                    val (rp1, rp2) = lines[i]
                    val mx = ((rp1.x + rp2.x) / 2f).coerceIn(50f, width - 50f)
                    val my = ((rp1.y + rp2.y) / 2f).coerceIn(50f, height - 50f)
                    drawLabel(canvas, lineDistanceLabels[i], mx, my)
                }
            }
        }
        // Draw anchored point dots
        for (rp in points) {
            val px = rp.x.coerceIn(0f, w); val py = rp.y.coerceIn(0f, h)
            canvas.drawCircle(px, py, 4f, dotP)
            canvas.drawCircle(px, py, 10f, dotRingP)
        }
    }

    private fun drawLevel(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val bubbleRadius = 120f  // circle radius
        val dotR = 12f  // bubble dot radius

        // Outer circle
        val circleP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#40FFFFFF")
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        c.drawCircle(cx, cy, bubbleRadius, circleP)

        // Crosshair
        c.drawLine(cx - 20f, cy, cx + 20f, cy, crossP)
        c.drawLine(cx, cy - 20f, cx, cy + 20f, crossP)

        // Center target rings
        val ring30 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#20FFFFFF")
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        c.drawCircle(cx, cy, 30f, ring30)
        c.drawCircle(cx, cy, 60f, ring30)

        // Bubble dot — moves based on tilt
        val maxOffset = bubbleRadius - dotR - 4f
        val tiltRad = Math.toRadians(levelTiltDeg.toDouble())
        val isLevel = levelTiltDeg < 1.0f
        val bubbleColor = if (isLevel) Color.parseColor("#00FF88") else Color.parseColor("#FFCC00")
        val bubbleP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = bubbleColor
            style = Paint.Style.FILL
        }
        // Clamp offset to circle
        val rawOffset = (tiltRad * 200).toFloat()  // scale factor for visual movement
        val bx = cx + rawOffset.coerceIn(-maxOffset, maxOffset)
        val by = cy + rawOffset.coerceIn(-maxOffset, maxOffset)
        c.drawCircle(bx, by, dotR, bubbleP)

        // Glow for level state
        if (isLevel) {
            val glowP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#3000FF88")
                style = Paint.Style.FILL
                maskFilter = BlurMaskFilter(8f, BlurMaskFilter.Blur.NORMAL)
            }
            c.drawCircle(bx, by, dotR + 4f, glowP)

            // Level indicator text
            val levelText = "✓ 水平"
            val txtP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#00FF88")
                textSize = 28f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            c.drawText(levelText, cx, cy + bubbleRadius + 50f, txtP)
        } else {
            val tiltText = String.format("%.1f°", levelTiltDeg)
            val txtP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FFCC00")
                textSize = 32f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
                setShadowLayer(4f, 1f, 1f, Color.BLACK)
            }
            c.drawText(tiltText, cx, cy + bubbleRadius + 50f, txtP)

            // Direction indicator
            val dirText = "偏斜"
            val dirP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#88FFFFFF")
                textSize = 18f
                textAlign = Paint.Align.CENTER
            }
            c.drawText(dirText, cx, cy + bubbleRadius + 80f, dirP)
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

    /** Apple-style endpoint marker: ⊕ crosshair with concentric ring. */
    private val endpointLineP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val endpointRingP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 1.5f
    }
    private val endpointRingOuterP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#60FFFFFF"); style = Paint.Style.STROKE; strokeWidth = 1f
    }
    private fun drawEndpointMarker(c: Canvas, x: Float, y: Float, anchored: Boolean) {
        val r = 16f; val tick = 10f
        // Crosshair
        c.drawLine(x - tick, y, x + tick, y, endpointLineP)
        c.drawLine(x, y - tick, x, y + tick, endpointLineP)
        // Inner ring
        c.drawCircle(x, y, r, endpointRingP)
        // Outer ring (faded) for anchored point
        if (anchored) c.drawCircle(x, y, r + 6f, endpointRingOuterP)
        // Center dot
        c.drawCircle(x, y, 2.5f, dotP)
    }

    /** Small depth label near endpoint (e.g. "120cm"). */
    private val depthTxtP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCFFFFFF"); textSize = 18f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val depthTxtBgP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#99000000"); style = Paint.Style.FILL
    }
    private fun drawDepthLabel(c: Canvas, text: String, x: Float, y: Float, above: Boolean) {
        depthTxtP.getTextBounds(text, 0, text.length, rRect)
        val w = rRect.width() / 2f + 8f; val h = rRect.height() / 2f + 4f
        val ly = if (above) y - 28f else y + 36f
        val rect = RectF(x - w, ly - h, x + w, ly + h)
        c.drawRoundRect(rect, 6f, 6f, depthTxtBgP)
        c.drawText(text, x, ly + rRect.height() / 2f, depthTxtP)
    }

    /** Level indicator badge (Apple style: green pill when device is level). */
    private val levelTxtP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00FF88"); textSize = 20f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val levelBgP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B3000000"); style = Paint.Style.FILL
    }
    private val levelBorderP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8000FF88"); style = Paint.Style.STROKE; strokeWidth = 1.5f
    }
    private fun drawLevelBadge(c: Canvas) {
        val text = "◎ 水平"
        levelTxtP.getTextBounds(text, 0, text.length, rRect)
        val cx = width / 2f; val y = 50f
        val w = rRect.width() / 2f + 16f; val h = rRect.height() / 2f + 8f
        val rect = RectF(cx - w, y - h, cx + w, y + h)
        c.drawRoundRect(rect, h, h, levelBgP)
        c.drawRoundRect(rect, h, h, levelBorderP)
        c.drawText(text, cx, y + rRect.height() / 2f, levelTxtP)
    }

    private fun drawLabel(c: Canvas, text: String, x: Float, y: Float) {
        txtP.getTextBounds(text, 0, text.length, rRect)
        val w = rRect.width()/2f + 14f; val h = rRect.height()/2f + 8f; val ly = y - 25f
        val rect = RectF(x - w, ly - h, x + w, ly + h)
        c.drawRoundRect(rect, h, h, txtBgP); c.drawRoundRect(rect, h, h, txtBorderP)
        c.drawText(text, x, ly + rRect.height()/2f, txtP)
    }

    private var sweepTextCached: String = ""
    private var sweepTextBounds: Rect = Rect()
    fun updateSweepText(text: String) {
        sweepTextCached = text
        sweepTxtP.getTextBounds(text, 0, text.length, sweepTextBounds)
    }

    // Sweep with Bezier smoothing + ruler/grid
    private fun drawSweep(c: Canvas) {
        val cx = width/2f; val cy = height/2f
        val cBot = height - 60f; val cTop = height - 200f

        if (sweepHistory.size >= 2) {
            rPath.reset()
            val maxD = sweepHistory.maxOfOrNull { it.second } ?: 200f
            val minD = sweepHistory.minOfOrNull { it.second } ?: 0f
            val range = (maxD - minD).coerceAtLeast(50f)

            // Ruler/grid lines
            val rulerCount = AppConstants.SWEEP_RULER_COUNT
            for (i in 0..rulerCount) {
                val frac = i.toFloat() / rulerCount
                val py = cBot - frac * (cBot - cTop)
                c.drawLine(0f, py, width.toFloat(), py, sweepGridP)
                val distVal = minD + frac * range
                c.drawText(String.format("%.0f", distVal), 55f, py - 4f, sweepRulerTxtP)
            }

            // Trail
            val firstX = sweepHistory[0].first
            val firstY = cBot - ((sweepHistory[0].second - minD) / range) * (cBot - cTop)
            rPath.moveTo(firstX, firstY)
            for (i in 1 until sweepHistory.size) {
                val prevX = sweepHistory[i - 1].first
                val prevY = cBot - ((sweepHistory[i - 1].second - minD) / range) * (cBot - cTop)
                val currX = sweepHistory[i].first
                val currY = cBot - ((sweepHistory[i].second - minD) / range) * (cBot - cTop)
                val cpx = (prevX + currX) / 2f; val cpy = (prevY + currY) / 2f
                rPath.quadTo(prevX, prevY, cpx, cpy)
            }
            c.drawPath(rPath, sweepTrailP)
        }

        c.drawLine(cx-40, cy, cx+40, cy, sweepCP); c.drawLine(cx, cy-40, cx, cy+40, sweepCP); c.drawCircle(cx, cy, 8f, sweepDotP)
        if (sweepDistanceCm > 0 && sweepTextCached.isNotEmpty()) {
            val w = sweepTextBounds.width()/2f + 20f; val h = sweepTextBounds.height()/2f + 20f
            c.drawRoundRect(cx-w, cy-70-h, cx+w, cy-70+h, 16f, 16f, sweepTxtBgP)
            c.drawText(sweepTextCached, cx - sweepTextBounds.width()/2f, cy - 70 + sweepTextBounds.height()/2f, sweepTxtP)
        }
    }

    // First-time tutorial
    private fun drawTutorial(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), tutorialBgP)

        c.drawText("📐 AR测距", cx, cy - 180f, tutorialTxtP)
        c.drawText("点击屏幕测量距离", cx, cy - 130f, tutorialSubTxtP)

        // Tap animation
        val tapX = cx; val tapY = cy + 20f
        val phase = (System.currentTimeMillis() % 2000) / 2000f
        val dotAlpha = ((1f - phase) * 255).toInt()
        val ringR = 10f + phase * 50f
        tutorialDotP.alpha = dotAlpha
        tutorialHandP.alpha = (dotAlpha * 0.6f).toInt()
        c.drawCircle(tapX, tapY, 8f, tutorialDotP)
        c.drawCircle(tapX, tapY, ringR, tutorialHandP)

        // Finger icon
        c.drawText("👆", cx - 12f, tapY + 60f, tutorialTxtP)

        c.drawText("单点 · 两点 · 面积 · 扫掠", cx, cy + 130f, tutorialSubTxtP)
        c.drawText("长按距离切换单位", cx, cy + 170f, tutorialSubTxtP)
        c.drawText("点击任意位置开始", cx, cy + 230f, tutorialTxtP.apply { textSize = 28f })
        tutorialTxtP.textSize = 32f // restore
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (scaleDetector.isInProgress) return true
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (showTutorial) { showTutorial = false; invalidate(); return true }
                if (sweepMode) onSweepMove?.invoke(event.x, event.y)
                else onTap?.invoke(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (sweepMode) { onSweepMove?.invoke(event.x, event.y); return true }
                onMove?.invoke(event.x, event.y); return true
            }
        }
        return super.onTouchEvent(event)
    }
}
