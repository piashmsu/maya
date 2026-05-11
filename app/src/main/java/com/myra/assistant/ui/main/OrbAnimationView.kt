package com.myra.assistant.ui.main

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The big animated orb at the center of the MYRA screen. Renders 7 layers in
 * order:
 *
 *  1. Outer radial glow
 *  2. Core orb sphere (RadialGradient)
 *  3. 3 rotating dashed rings
 *  4. Wave rings (sine, amplitude-reactive)
 *  5. Thinking arc (spinning, only in [State.THINKING])
 *  6. Orbiting particles (active/speaking)
 *  7. Inner highlight (top-left, sphere illusion)
 */
class OrbAnimationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    enum class State { IDLE, LISTENING, SPEAKING, THINKING }

    private var currentState = State.IDLE
    private var amplitudeValue = 0f

    // Animators ------------------------------------------------------------

    private val pulseAnimator = ValueAnimator.ofFloat(1f, 1.15f, 1f).apply {
        duration = 1500L
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { invalidate() }
    }
    private val rotationAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 8000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { invalidate() }
    }
    private val waveAnimator = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
        duration = 1800L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { invalidate() }
    }
    private val thinkingAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 1100L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { invalidate() }
    }
    private val particleAnimator = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
        duration = 4500L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { invalidate() }
    }

    // Paints --------------------------------------------------------------

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.WHITE
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
    }

    private val arcRect = RectF()

    init {
        pulseAnimator.start()
        rotationAnimator.start()
        waveAnimator.start()
        particleAnimator.start()
    }

    // Public API ----------------------------------------------------------

    fun setOrbState(state: State) {
        if (state == currentState) return
        currentState = state
        if (state == State.THINKING) {
            if (!thinkingAnimator.isStarted) thinkingAnimator.start()
        } else if (thinkingAnimator.isStarted) {
            thinkingAnimator.cancel()
        }
        invalidate()
    }

    fun setAmplitude(rms: Float) {
        amplitudeValue = rms.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pulseAnimator.cancel()
        rotationAnimator.cancel()
        waveAnimator.cancel()
        thinkingAnimator.cancel()
        particleAnimator.cancel()
    }

    // Drawing -------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val baseRadius = min(width, height) / 2f * 0.55f
        val pulseScale = pulseAnimator.animatedValue as? Float ?: 1f
        val coreRadius = baseRadius * pulseScale * (1f + amplitudeValue * 0.06f)

        drawGlow(canvas, cx, cy, coreRadius * 1.6f)
        drawCore(canvas, cx, cy, coreRadius)
        drawRotatingRings(canvas, cx, cy, coreRadius)
        drawWaveRings(canvas, cx, cy, coreRadius)
        if (currentState == State.THINKING) {
            drawThinkingArc(canvas, cx, cy, coreRadius)
        }
        if (currentState == State.SPEAKING || currentState == State.LISTENING) {
            drawParticles(canvas, cx, cy, coreRadius)
        }
        drawHighlight(canvas, cx, cy, coreRadius)
    }

    private fun colorsForState(): Pair<Int, Int> = when (currentState) {
        State.IDLE -> Color.parseColor("#B71C1C") to Color.parseColor("#880E4F")
        State.LISTENING -> Color.parseColor("#FF1744") to Color.parseColor("#D500F9")
        State.SPEAKING -> Color.parseColor("#E040FB") to Color.parseColor("#FF1744")
        State.THINKING -> Color.parseColor("#40C4FF") to Color.parseColor("#00B0FF")
    }

    private fun drawGlow(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val (inner, outer) = colorsForState()
        val gradient = RadialGradient(
            cx, cy, radius,
            intArrayOf(
                (inner and 0x00FFFFFF) or (200 shl 24),
                (outer and 0x00FFFFFF) or (60 shl 24),
                Color.TRANSPARENT,
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        glowPaint.shader = gradient
        canvas.drawCircle(cx, cy, radius, glowPaint)
    }

    private fun drawCore(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val (inner, outer) = colorsForState()
        val gradient = RadialGradient(
            cx - radius * 0.2f, cy - radius * 0.25f, radius,
            intArrayOf(inner, inner, outer, Color.BLACK),
            floatArrayOf(0f, 0.3f, 0.75f, 1f),
            Shader.TileMode.CLAMP,
        )
        corePaint.shader = gradient
        canvas.drawCircle(cx, cy, radius, corePaint)
    }

    private fun drawRotatingRings(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val angle = rotationAnimator.animatedValue as? Float ?: 0f
        ringPaint.color = (Color.WHITE and 0x00FFFFFF) or (110 shl 24)
        val ringRadii = floatArrayOf(radius * 1.05f, radius * 1.2f, radius * 1.35f)
        val dashes = floatArrayOf(20f, 14f)
        ringPaint.pathEffect = DashPathEffect(dashes, 0f)
        for ((i, r) in ringRadii.withIndex()) {
            val rotationOffset = angle * (if (i % 2 == 0) 1f else -1f)
            canvas.save()
            canvas.rotate(rotationOffset, cx, cy)
            canvas.drawCircle(cx, cy, r, ringPaint)
            canvas.restore()
        }
        ringPaint.pathEffect = null
    }

    private fun drawWaveRings(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val (inner, outer) = colorsForState()
        val waveOffset = waveAnimator.animatedValue as? Float ?: 0f
        val amp = 6f + amplitudeValue * 22f + (if (currentState == State.SPEAKING) 6f else 0f)
        for (ring in 0 until 3) {
            val r0 = radius * (1.0f + 0.12f * ring)
            wavePaint.color = blend(inner, outer, ring / 3f).withAlpha(170 - ring * 40)
            val steps = 64
            var prevX = 0f
            var prevY = 0f
            for (i in 0..steps) {
                val theta = (i.toFloat() / steps) * (Math.PI * 2).toFloat()
                val wobble = sin(theta * 4 + waveOffset + ring) * amp
                val r = r0 + wobble
                val x = cx + r * cos(theta)
                val y = cy + r * sin(theta)
                if (i > 0) canvas.drawLine(prevX, prevY, x, y, wavePaint)
                prevX = x
                prevY = y
            }
        }
    }

    private fun drawThinkingArc(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val angle = thinkingAnimator.animatedValue as? Float ?: 0f
        arcRect.set(cx - radius * 1.1f, cy - radius * 1.1f, cx + radius * 1.1f, cy + radius * 1.1f)
        arcPaint.color = Color.parseColor("#40C4FF")
        canvas.drawArc(arcRect, angle, 75f, false, arcPaint)
        arcRect.set(cx - radius * 1.25f, cy - radius * 1.25f, cx + radius * 1.25f, cy + radius * 1.25f)
        arcPaint.color = Color.parseColor("#00B0FF").withAlpha(180)
        canvas.drawArc(arcRect, -angle * 0.8f, 60f, false, arcPaint)
    }

    private fun drawParticles(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val phase = particleAnimator.animatedValue as? Float ?: 0f
        val (inner, _) = colorsForState()
        particlePaint.color = inner.withAlpha(220)
        val orbit = radius * 1.45f
        val count = 12
        for (i in 0 until count) {
            val theta = phase + (Math.PI * 2 * i / count).toFloat()
            val pulse = 1f + 0.5f * sin(phase * 2 + i.toFloat())
            val px = cx + orbit * cos(theta)
            val py = cy + orbit * sin(theta)
            canvas.drawCircle(px, py, 4f * pulse, particlePaint)
        }
    }

    private fun drawHighlight(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val highlight = RadialGradient(
            cx - radius * 0.35f, cy - radius * 0.4f, radius * 0.6f,
            intArrayOf(Color.WHITE.withAlpha(120), Color.WHITE.withAlpha(0)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        highlightPaint.shader = highlight
        canvas.drawCircle(cx, cy, radius, highlightPaint)
    }

    // -- Color helpers. ---------------------------------------------------

    private fun blend(a: Int, b: Int, t: Float): Int {
        val tt = t.coerceIn(0f, 1f)
        val ar = (Color.red(a) * (1f - tt) + Color.red(b) * tt).toInt()
        val ag = (Color.green(a) * (1f - tt) + Color.green(b) * tt).toInt()
        val ab = (Color.blue(a) * (1f - tt) + Color.blue(b) * tt).toInt()
        return Color.rgb(ar, ag, ab)
    }

    private fun Int.withAlpha(a: Int): Int = (this and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)
}
