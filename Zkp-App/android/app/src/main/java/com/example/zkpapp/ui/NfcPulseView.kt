package com.example.zkpapp.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Radiating NFC-style arcs — indicates "waiting for NFC chip".
 *
 * Draws three staggered arcs pulsing outward on both sides of the view.
 * Respects reduce-motion setting (shows static final state when enabled).
 */
class NfcPulseView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private var phase = 0f
    private var animator: ValueAnimator? = null
    private var reduceMotion = false
    private var arcColor: Int = Color.parseColor("#00E0B8")
    private var arcCount: Int = 3
    private var strokeWidthPx: Float = 5f
    private val rect = RectF()

    init {
        startAnimation()
    }

    fun setArcColor(color: Int) {
        arcColor = color
        invalidate()
    }

    fun setReduceMotion(reduce: Boolean) {
        if (reduceMotion == reduce) return
        reduceMotion = reduce
        if (reduce) {
            animator?.cancel()
            phase = 1f
            invalidate()
        } else {
            startAnimation()
        }
    }

    private fun startAnimation() {
        if (reduceMotion) return
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1800L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val maxRadius = (minOf(width, height) / 2f) - strokeWidthPx
        if (maxRadius <= 0f) return
        val minRadius = maxRadius * 0.35f

        for (i in 0 until arcCount) {
            val arcPhase = ((phase - i * (1f / arcCount)) + 1f) % 1f
            val radius = minRadius + arcPhase * (maxRadius - minRadius)
            val alpha = ((1f - arcPhase) * 255f).toInt().coerceIn(0, 255)

            arcPaint.color = arcColor
            arcPaint.alpha = alpha
            arcPaint.strokeWidth = strokeWidthPx

            rect.set(cx - radius, cy - radius, cx + radius, cy + radius)

            // Right arc: -50° to +50° (sweep 100°)
            canvas.drawArc(rect, -50f, 100f, false, arcPaint)
            // Left arc: 130° to 230° (sweep 100°)
            canvas.drawArc(rect, 130f, 100f, false, arcPaint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }
}