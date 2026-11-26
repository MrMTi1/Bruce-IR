package com.example.bruceir.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class SignalView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var pattern: IntArray? = null
    private val paint = Paint().apply {
        color = Color.parseColor("#2196F3")
        strokeWidth = 4f
        style = Paint.Style.FILL
    }
    private val backgroundPaint = Paint().apply {
        color = Color.parseColor("#1AFFFFFF")
    }

    fun setPattern(newPattern: IntArray) {
        this.pattern = newPattern
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val p = pattern ?: return
        if (p.isEmpty()) return

        val w = width.toFloat()
        val h = height.toFloat()
        val totalDuration = p.sum().toFloat()
        
        var currentX = 0f
        var isHigh = true // IR patterns usually start with a mark (pulse)

        p.forEach { duration ->
            val barWidth = (duration / totalDuration) * w
            if (isHigh) {
                canvas.drawRect(currentX, h * 0.2f, currentX + barWidth, h * 0.8f, paint)
            } else {
                canvas.drawRect(currentX, h * 0.5f - 2, currentX + barWidth, h * 0.5f + 2, backgroundPaint)
            }
            currentX += barWidth
            isHigh = !isHigh
        }
    }
}
