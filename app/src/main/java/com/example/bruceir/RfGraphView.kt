package com.example.bruceir

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class RfGraphView(context: Context, attrs: AttributeSet) : View(context, attrs) {
    private val paint = Paint().apply {
        color = Color.GREEN
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private var data: IntArray = intArrayOf()

    fun updateData(newData: IntArray) {
        data = newData
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (data.isEmpty()) return

        val w = width.toFloat()
        val h = height.toFloat()
        val step = w / (data.size - 1)

        for (i in 0 until data.size - 1) {
            // Map RSSI (typically -100 to 0) to Y coordinate
            val y1 = h - ((data[i] + 100) / 100f * h)
            val y2 = h - ((data[i + 1] + 100) / 100f * h)
            canvas.drawLine(i * step, y1, (i + 1) * step, y2, paint)
        }
    }
}
