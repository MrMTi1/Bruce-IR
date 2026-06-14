package com.example.bruceir

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class WaterfallView(context: Context, attrs: AttributeSet) : View(context, attrs) {
    private var bitmap: Bitmap? = null
    private var canvasBitmap: Canvas? = null
    private val paint = Paint()
    private val matrix = Matrix()

    fun addRow(data: IntArray) {
        if (bitmap == null || bitmap?.width != data.size) {
            bitmap = Bitmap.createBitmap(data.size, 500, Bitmap.Config.ARGB_8888)
            canvasBitmap = Canvas(bitmap!!)
            canvasBitmap?.drawColor(Color.BLACK)
        }

        // Shift bitmap down
        val tempBitmap = Bitmap.createBitmap(bitmap!!)
        canvasBitmap?.drawBitmap(tempBitmap, 0f, 1f, null)

        // Draw new row at top
        for (i in data.indices) {
            val rssi = data[i]
            paint.color = rssiToColor(rssi)
            canvasBitmap?.drawPoint(i.toFloat(), 0f, paint)
        }

        invalidate()
    }

    private fun rssiToColor(rssi: Int): Int {
        // Simple heatmap: Blue (cold) to Red (hot)
        val normalized = (rssi + 100).coerceIn(0, 100) / 100f
        return Color.HSVToColor(floatArrayOf((1.0f - normalized) * 240f, 1f, normalized))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        bitmap?.let {
            // Scale bitmap to fill view
            val scaleX = width.toFloat() / it.width
            val scaleY = height.toFloat() / it.height
            matrix.reset()
            matrix.postScale(scaleX, scaleY)
            canvas.drawBitmap(it, matrix, null)
        }
    }
}
