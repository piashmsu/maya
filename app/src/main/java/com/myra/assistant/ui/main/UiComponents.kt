package com.myra.assistant.ui.main

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.myra.assistant.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A single chat message rendered in the bottom RecyclerView. */
data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * 20-bar waveform that animates toward a target amplitude. The bars lerp every
 * frame so the motion looks smooth even though the underlying mic RMS samples
 * are choppy.
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private val barCount = 20
    private val barHeights = FloatArray(barCount)
    private val barTargets = FloatArray(barCount)
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF1744")
    }
    private val barRect = RectF()
    private var animating = false
    private var amplitude = 0f

    private val frameTick = object : Runnable {
        override fun run() {
            if (!animating) return
            for (i in 0 until barCount) {
                barHeights[i] += (barTargets[i] - barHeights[i]) * 0.30f
            }
            invalidate()
            postDelayed(this, 33L)
        }
    }

    fun startAnimation() {
        if (animating) return
        animating = true
        post(frameTick)
    }

    fun stopAnimation() {
        animating = false
        amplitude = 0f
        for (i in 0 until barCount) {
            barTargets[i] = 0.05f
        }
        invalidate()
    }

    fun setAmplitude(rms: Float) {
        amplitude = rms.coerceIn(0f, 1f)
        for (i in 0 until barCount) {
            val center = barCount / 2f
            val distance = kotlin.math.abs(i - center) / center
            val falloff = 1f - 0.55f * distance
            val random = 0.4f + 0.6f * Math.random().toFloat()
            barTargets[i] = (0.08f + amplitude * falloff * random).coerceIn(0.05f, 1f)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val gap = w / (barCount * 2.4f)
        val barWidth = (w - gap * (barCount + 1)) / barCount
        for (i in 0 until barCount) {
            val barH = (barHeights[i].coerceAtLeast(0.05f)) * h
            val left = gap + i * (barWidth + gap)
            val top = (h - barH) / 2f
            barRect.set(left, top, left + barWidth, top + barH)
            val alpha = (150f + 105f * barHeights[i]).toInt().coerceIn(150, 255)
            barPaint.alpha = alpha
            canvas.drawRoundRect(barRect, barWidth / 3f, barWidth / 3f, barPaint)
        }
    }
}

/** RecyclerView adapter for the chat thread (user + MYRA). */
class ChatAdapter : RecyclerView.Adapter<ChatAdapter.VH>() {

    private val items = mutableListOf<ChatMessage>()
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.ENGLISH)

    fun submit(message: ChatMessage) {
        // De-dupe: skip if this is a MYRA message identical to the most-recent MYRA bubble.
        if (!message.isUser) {
            val lastMyra = items.lastOrNull { !it.isUser }
            if (lastMyra != null && lastMyra.text.trim() == message.text.trim()) return
        }
        items.add(message)
        notifyItemInserted(items.size - 1)
    }

    fun lastMyraText(): String? = items.lastOrNull { !it.isUser }?.text

    override fun getItemViewType(position: Int): Int =
        if (items[position].isUser) TYPE_USER else TYPE_MYRA

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val res = if (viewType == TYPE_USER) R.layout.item_chat_user else R.layout.item_chat_myra
        val v = LayoutInflater.from(parent.context).inflate(res, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.text.text = item.text
        holder.time?.text = timeFmt.format(Date(item.timestamp))
    }

    override fun getItemCount(): Int = items.size

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.chatText)
        val time: TextView? = view.findViewById(R.id.chatTime)
    }

    companion object {
        private const val TYPE_USER = 1
        private const val TYPE_MYRA = 2
    }
}
