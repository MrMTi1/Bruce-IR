package com.example.bruceir.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class BruceScreenView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint().apply {
        isAntiAlias = false
        style = Paint.Style.FILL
    }
    private val textPaint = Paint().apply {
        isAntiAlias = true
        typeface = Typeface.MONOSPACE
    }
    
    private var screenWidth = 240
    private var screenHeight = 135
    private var buffer: ByteArray? = null
    private var bitmap: Bitmap? = null
    private var canvasBitmap: Canvas? = null
    private var imageBaseUrl: String? = null
    private var imageAuthHeader: String? = null
    private var bleImageLoader: ((Int, String, (Bitmap?) -> Unit) -> Unit)? = null
    private val imageCache = ConcurrentHashMap<String, Bitmap>()
    private val loadingImages = ConcurrentHashMap.newKeySet<String>()
    private val imageLoader = Executors.newSingleThreadExecutor()

    /**
     * Configures access to the image files referenced by Bruce's DRAWIMAGE
     * screen-log commands.  The screen packet contains paths, not pixels.
     */
    fun configureImageLoading(baseUrl: String, authHeader: String) {
        imageBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        imageAuthHeader = authHeader
    }

    fun configureBleImageLoading(loader: (Int, String, (Bitmap?) -> Unit) -> Unit) {
        bleImageLoader = loader
    }

    fun updateScreen(data: ByteArray) {
        this.buffer = data
        invalidate()
    }

    fun appendScreenPacket(packet: ByteArray) {
        if (packet.size < 3 || packet[0] != 0xAA.toByte()) return
        val current = buffer
        buffer = when {
            packet[2] == 0.toByte() -> packet
            current == null -> packet
            current.size + packet.size > MAX_STREAM_BYTES -> packet
            else -> current + packet
        }
        invalidate()
    }

    fun updateFrame(data: ByteArray) {
        if (data.size < 8 || data[0] != 'B'.code.toByte() || data[1] != 'R'.code.toByte() ||
            data[2] != 'F'.code.toByte() || data[3] != '1'.code.toByte()) return
        screenWidth = readU16(data, 4)
        screenHeight = readU16(data, 6)
        val expected = screenWidth * screenHeight * 2
        if (expected <= 0 || data.size < expected + 8) return
        val pixels = IntArray(screenWidth * screenHeight)
        var p = 8
        for (i in pixels.indices) {
            pixels[i] = readColor(data, p)
            p += 2
        }
        bitmap = Bitmap.createBitmap(pixels, screenWidth, screenHeight, Bitmap.Config.ARGB_8888)
        canvasBitmap = null
        buffer = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val data = buffer
        if (data == null) {
            bitmap?.let { canvas.drawBitmap(it, null, Rect(0, 0, width, height), null) }
            return
        }
        
        if (bitmap == null || bitmap?.width != width || bitmap?.height != height) {
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            canvasBitmap = Canvas(bitmap!!)
        }

        val c = canvasBitmap!!
        // The CRT overlay below deliberately changes alpha; each replay must
        // start opaque so it cannot fade subsequent shapes or images.
        paint.alpha = 255
        var pos = 0
        
        while (pos < data.size) {
            if (data[pos].toInt() and 0xFF != 0xAA || data.size - pos < 3) {
                pos++
                continue
            }
            
            val packetSize = data[pos + 1].toInt() and 0xFF
            if (packetSize < 3 || packetSize > data.size - pos) {
                pos++
                continue
            }
            val func = data[pos + 2].toInt() and 0xFF
            
            try {
                processCommand(c, func, data, pos + 3, pos + packetSize)
            } catch (e: Exception) {
                // Skip faulty command
            }
            pos += packetSize
        }
        
        // Draw the composed bitmap to screen
        bitmap?.let {
            canvas.drawBitmap(it, null, Rect(0, 0, width, height), null)
        }

        // CRT Scanline Effect (Overlay)
        paint.color = Color.BLACK
        paint.alpha = 40
        for (i in 0 until height step 4) {
            canvas.drawRect(0f, i.toFloat(), width.toFloat(), (i + 1).toFloat(), paint)
        }
    }

    private fun processCommand(c: Canvas, func: Int, data: ByteArray, start: Int, end: Int) {
        when (func) {
            0 -> { // FILLSCREEN
                require(start + 2 <= end)
                val color = readColor(data, start)
                c.drawColor(color)
            }
            2 -> { // FILLRECT
                require(start + 10 <= end)
                val x = readU16(data, start)
                val y = readU16(data, start + 2)
                val w = readU16(data, start + 4)
                val h = readU16(data, start + 6)
                val color = readColor(data, start + 8)
                paint.color = color
                c.drawRect(scaleX(x), scaleY(y), scaleX(x + w), scaleY(y + h), paint)
            }
            1, 3, 4 -> {
                require(start + (if (func == 1) 10 else 12) <= end)
                val x = readS16(data, start)
                val y = readS16(data, start + 2)
                val w = readS16(data, start + 4)
                val h = readS16(data, start + 6)
                val radius = if (func == 1) 0 else readS16(data, start + 8)
                paint.color = readColor(data, start + if (func == 1) 8 else 10)
                paint.style = if (func == 4) Paint.Style.FILL else Paint.Style.STROKE
                if (radius == 0) {
                    c.drawRect(scaleX(x), scaleY(y), scaleX(x + w), scaleY(y + h), paint)
                } else {
                    c.drawRoundRect(scaleX(x), scaleY(y), scaleX(x + w), scaleY(y + h),
                        scaleX(radius), scaleY(radius), paint)
                }
                paint.style = Paint.Style.FILL
            }
            5, 6 -> {
                require(start + 8 <= end)
                paint.color = readColor(data, start + 6)
                paint.style = if (func == 6) Paint.Style.FILL else Paint.Style.STROKE
                c.drawCircle(scaleX(readS16(data, start)), scaleY(readS16(data, start + 2)),
                    scaleX(readS16(data, start + 4)), paint)
                paint.style = Paint.Style.FILL
            }
            7, 8 -> {
                require(start + 14 <= end)
                paint.color = readColor(data, start + 12)
                paint.style = if (func == 8) Paint.Style.FILL else Paint.Style.STROKE
                val path = Path().apply {
                    moveTo(scaleX(readS16(data, start)), scaleY(readS16(data, start + 2)))
                    lineTo(scaleX(readS16(data, start + 4)), scaleY(readS16(data, start + 6)))
                    lineTo(scaleX(readS16(data, start + 8)), scaleY(readS16(data, start + 10)))
                    close()
                }
                c.drawPath(path, paint)
                paint.style = Paint.Style.FILL
            }
            9, 10 -> {
                require(start + 10 <= end)
                paint.color = readColor(data, start + 8)
                paint.style = if (func == 10) Paint.Style.FILL else Paint.Style.STROKE
                c.drawOval(scaleX(readS16(data, start) - readS16(data, start + 4)),
                    scaleY(readS16(data, start + 2) - readS16(data, start + 6)),
                    scaleX(readS16(data, start) + readS16(data, start + 4)),
                    scaleY(readS16(data, start + 2) + readS16(data, start + 6)), paint)
                paint.style = Paint.Style.FILL
            }
            11 -> {
                require(start + 10 <= end)
                paint.color = readColor(data, start + 8)
                c.drawLine(scaleX(readS16(data, start)), scaleY(readS16(data, start + 2)),
                    scaleX(readS16(data, start + 4)), scaleY(readS16(data, start + 6)), paint)
            }
            12 -> {
                require(start + 16 <= end)
                paint.color = readColor(data, start + 12)
                val x = readS16(data, start)
                val y = readS16(data, start + 2)
                val r = readS16(data, start + 4)
                val rect = RectF(scaleX(x - r), scaleY(y - r), scaleX(x + r), scaleY(y + r))
                c.drawArc(rect, readS16(data, start + 8).toFloat(),
                    (readS16(data, start + 10) - readS16(data, start + 8)).toFloat(), false, paint)
            }
            13 -> {
                require(start + 14 <= end)
                paint.color = readColor(data, start + 10)
                paint.strokeWidth = scaleX(readS16(data, start + 8)).coerceAtLeast(1f)
                c.drawLine(scaleX(readS16(data, start)), scaleY(readS16(data, start + 2)),
                    scaleX(readS16(data, start + 4)), scaleY(readS16(data, start + 6)), paint)
                paint.strokeWidth = 1f
            }
            19 -> {
                require(start + 6 <= end)
                paint.color = readColor(data, start + 4)
                c.drawPoint(scaleX(readS16(data, start)), scaleY(readS16(data, start + 2)), paint)
            }
            20, 21 -> {
                require(start + 8 <= end)
                paint.color = readColor(data, start + 6)
                val x = readS16(data, start)
                val y = readS16(data, start + 2)
                if (func == 20) c.drawLine(scaleX(x), scaleY(y), scaleX(x), scaleY(y + readS16(data, start + 4)), paint)
                else c.drawLine(scaleX(x), scaleY(y), scaleX(x + readS16(data, start + 4)), scaleY(y), paint)
            }
            16, 14, 15 -> { // DRAWSTRING
                require(start + 10 <= end)
                val x = readU16(data, start)
                val y = readU16(data, start + 2)
                val size = readU16(data, start + 4)
                val color = readColor(data, start + 6)
                val bg = readColor(data, start + 8)
                val textLength = end - (start + 10)
                require(textLength >= 0)
                val text = String(data, start + 10, textLength)
                
                textPaint.color = color
                textPaint.textSize = size * 12f * (width / screenWidth.toFloat())
                textPaint.textAlign = when (func) {
                    14 -> Paint.Align.CENTER
                    15 -> Paint.Align.RIGHT
                    else -> Paint.Align.LEFT
                }
                c.drawText(text, scaleX(x), scaleY(y + (size * 8)), textPaint)
                textPaint.textAlign = Paint.Align.LEFT
            }
            18 -> { // DRAWIMAGE: x, y, centered, duration, filesystem, file path
                require(start + 9 <= end)
                val x = readS16(data, start)
                val y = readS16(data, start + 2)
                val centered = readU16(data, start + 4) == 1
                val fs = data[start + 8].toInt() and 0xFF
                val file = String(data, start + 9, end - (start + 9)).trimEnd('\u0000')
                if (file.isNotEmpty()) drawOrLoadImage(c, fs, file, x, y, centered)
            }
            99 -> { // SCREEN_INFO
                require(start + 4 <= end)
                screenWidth = readU16(data, start)
                screenHeight = readU16(data, start + 2)
            }
        }
    }

    private fun readU16(data: ByteArray, pos: Int): Int {
        return ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
    }

    private fun readS16(data: ByteArray, pos: Int): Int = readU16(data, pos).let { if (it and 0x8000 != 0) it - 0x10000 else it }

    private fun readColor(data: ByteArray, pos: Int): Int {
        val c565 = readU16(data, pos)
        val r = (c565 shr 11) and 0x1F
        val g = (c565 shr 5) and 0x3F
        val b = c565 and 0x1F
        return Color.rgb((r shl 3) or (r shr 2), (g shl 2) or (g shr 4), (b shl 3) or (b shr 2))
    }

    private fun scaleX(x: Int): Float = x * (width / screenWidth.toFloat())
    private fun scaleY(y: Int): Float = y * (height / screenHeight.toFloat())

    private fun drawOrLoadImage(c: Canvas, fs: Int, file: String, x: Int, y: Int, centered: Boolean) {
        val key = "$fs:$file"
        val image = imageCache[key]
        if (image != null) {
            val drawX = if (centered) x + (screenWidth - image.width) / 2 else x
            val drawY = if (centered) y + (screenHeight - image.height) / 2 else y
            c.drawBitmap(
                image,
                null,
                RectF(scaleX(drawX), scaleY(drawY), scaleX(drawX + image.width), scaleY(drawY + image.height)),
                paint
            )
            return
        }

        val bleLoader = bleImageLoader
        if (bleLoader != null && loadingImages.add(key)) {
            bleLoader(fs, file) { loaded ->
                if (loaded != null) imageCache[key] = loaded
                loadingImages.remove(key)
                post { invalidate() }
            }
            return
        }

        val baseUrl = imageBaseUrl ?: return
        if (!loadingImages.add(key)) return

        imageLoader.execute {
            try {
                val encodedFile = URLEncoder.encode(file, "UTF-8")
                val connection = URL("${baseUrl}file?fs=$fs&name=$encodedFile&action=image")
                    .openConnection() as HttpURLConnection
                connection.connectTimeout = 2_000
                connection.readTimeout = 2_000
                imageAuthHeader?.let { connection.setRequestProperty("Authorization", it) }
                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    connection.inputStream.use { stream ->
                        BitmapFactory.decodeStream(stream)?.let { imageCache[key] = it }
                    }

                }
                connection.disconnect()
            } catch (_: Exception) {
                // The next screen update can retry a temporarily unavailable image.
            } finally {
                loadingImages.remove(key)
                post { invalidate() }
            }
        }
    }

    private companion object {
        const val MAX_STREAM_BYTES = 16 * 1024
    }
}
