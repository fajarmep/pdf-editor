package com.fajar.pdfeditor.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

class SignaturePadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val path = Path()
    private val paint = Paint().apply {
        isAntiAlias = true
        isDither = true
        color = Color.parseColor("#0F172A")
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 6f
    }

    private var lastX = 0f
    private var lastY = 0f
    private var boundsMinX = Float.MAX_VALUE
    private var boundsMinY = Float.MAX_VALUE
    private var boundsMaxX = 0f
    private var boundsMaxY = 0f
    private var hasDrawn = false

    fun setStrokeColor(color: Int) {
        paint.color = color
        invalidate()
    }

    fun clear() {
        path.reset()
        hasDrawn = false
        boundsMinX = Float.MAX_VALUE
        boundsMinY = Float.MAX_VALUE
        boundsMaxX = 0f
        boundsMaxY = 0f
        invalidate()
    }

    fun isEmpty(): Boolean = !hasDrawn

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                path.moveTo(x, y)
                lastX = x
                lastY = y
                updateBounds(x, y)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = Math.abs(x - lastX)
                val dy = Math.abs(y - lastY)
                if (dx >= 2 || dy >= 2) {
                    path.quadTo(lastX, lastY, (x + lastX) / 2, (y + lastY) / 2)
                    lastX = x
                    lastY = y
                    hasDrawn = true
                    updateBounds(x, y)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                path.lineTo(x, y)
                updateBounds(x, y)
                invalidate()
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun updateBounds(x: Float, y: Float) {
        boundsMinX = min(boundsMinX, x)
        boundsMinY = min(boundsMinY, y)
        boundsMaxX = max(boundsMaxX, x)
        boundsMaxY = max(boundsMaxY, y)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawPath(path, paint)
    }

    fun exportBitmap(): Bitmap? {
        if (!hasDrawn || width <= 0 || height <= 0) return null

        val pad = 12f
        val left = max(0f, boundsMinX - pad)
        val top = max(0f, boundsMinY - pad)
        val right = min(width.toFloat(), boundsMaxX + pad)
        val bottom = min(height.toFloat(), boundsMaxY + pad)

        val cropW = max(10, (right - left).toInt())
        val cropH = max(10, (bottom - top).toInt())

        val fullBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(fullBitmap)
        canvas.drawPath(path, paint)

        return try {
            Bitmap.createBitmap(fullBitmap, left.toInt(), top.toInt(), cropW, cropH)
        } catch (_: Exception) {
            fullBitmap
        }
    }
}
