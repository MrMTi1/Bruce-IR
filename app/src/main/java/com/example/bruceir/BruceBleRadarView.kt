package com.example.bruceir

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.AttributeSet
import android.view.View

class BruceBleRadarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), SensorEventListener {

    private var smoothRssi: Float = -90f

    var rssi: Int = -100
        set(value) {
            field = value
            smoothRssi = smoothRssi * 0.7f + value.toFloat() * 0.3f
            postInvalidateOnAnimation()
        }

    var azimuthDegrees: Float = 0f
        set(value) {
            field = value
            postInvalidateOnAnimation()
        }

    private var pulseRadiusMs: Float = 0f

    private val bgPaint = Paint().apply { color = 0xFF141B25.toInt(); style = Paint.Style.FILL }
    private val ringPaint = Paint().apply { color = 0xFF283545.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f; pathEffect = DashPathEffect(floatArrayOf(12f, 12f), 0f) }
    private val wavePaint = Paint().apply { color = 0x6643C59E.toInt(); style = Paint.Style.STROKE; strokeWidth = 4f }
    private val targetPaint = Paint().apply { style = Paint.Style.FILL }
    private val arrowPaint = Paint().apply { color = 0xFF00E5FF.toInt(); style = Paint.Style.FILL_AND_STROKE; strokeWidth = 8f }

    private var sensorManager: SensorManager? = null
    private var rotSensor: Sensor? = null

    init {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        rotSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    }

    fun startSensors() {
        rotSensor?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stopSensors() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
            val rotMatrix = FloatArray(9)
            val orientation = FloatArray(3)
            SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
            SensorManager.getOrientation(rotMatrix, orientation)
            azimuthDegrees = Math.toDegrees(orientation[0].toDouble()).toFloat()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val cx = width / 2f
        val cy = height / 2f
        val maxRadius = Math.min(cx, cy) - 30f

        canvas.drawCircle(cx, cy, maxRadius, bgPaint)
        canvas.drawCircle(cx, cy, maxRadius, ringPaint)
        canvas.drawCircle(cx, cy, maxRadius * 0.7f, ringPaint)
        canvas.drawCircle(cx, cy, maxRadius * 0.4f, ringPaint)

        val normRssi = ((100f + smoothRssi) / 70f).coerceIn(0.1f, 1.0f)
        val targetRadius = maxRadius * normRssi * 0.7f

        targetPaint.color = when {
            smoothRssi >= -60f -> 0xCC43C59E.toInt()
            smoothRssi >= -75f -> 0xCCFFD54F.toInt()
            else -> 0xCCE53935.toInt()
        }

        pulseRadiusMs = (pulseRadiusMs + 4f) % maxRadius
        wavePaint.color = Color.argb((200 * (1f - pulseRadiusMs / maxRadius)).toInt().coerceIn(0, 200), 67, 197, 158)
        canvas.drawCircle(cx, cy, pulseRadiusMs, wavePaint)

        canvas.drawCircle(cx, cy, Math.max(targetRadius, 24f), targetPaint)

        canvas.save()
        canvas.rotate(-azimuthDegrees, cx, cy)

        val path = Path()
        val arrowHeadY = cy - maxRadius + 20f
        path.moveTo(cx, arrowHeadY)
        path.lineTo(cx - 20f, arrowHeadY + 40f)
        path.lineTo(cx + 20f, arrowHeadY + 40f)
        path.close()
        canvas.drawPath(path, arrowPaint)

        canvas.restore()

        postInvalidateOnAnimation()
    }
}
