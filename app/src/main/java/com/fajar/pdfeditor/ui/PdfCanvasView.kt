package com.fajar.pdfeditor.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.fajar.pdfeditor.model.Annotation
import com.fajar.pdfeditor.model.PageState
import com.fajar.pdfeditor.model.ToolMode
import kotlin.math.max
import kotlin.math.min

class PdfCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var pageBitmap: Bitmap? = null
        private set
    var pageState: PageState? = null
        private set

    var currentTool: ToolMode = ToolMode.SELECT
        set(value) {
            field = value
            clearSelection()
            invalidate()
        }

    var penColor: Int = Color.parseColor("#0F172A")
    var penWidth: Float = 5f
    var highlighterColor: Int = Color.parseColor("#66FEF08A") // translucent yellow
    var highlighterWidth: Float = 24f

    private var selectedAnnotation: Annotation? = null

    // Paint objects
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val penPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val whiteoutPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val textPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }
    private val selectionPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#2563EB")
    }
    private val handlePaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#2563EB")
    }

    // Touch interaction tracking
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var activeDragMode = DragMode.NONE
    private var activeCorner = ResizeCorner.NONE
    private var currentStroke: Annotation.StrokeItem? = null
    private var activeTempRect = RectF()

    private enum class DragMode { NONE, MOVE, RESIZE, DRAWING }
    private enum class ResizeCorner { NONE, BOTTOM_RIGHT, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT }

    fun setPage(bitmap: Bitmap, state: PageState) {
        pageBitmap = bitmap
        pageState = state
        selectedAnnotation = null
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val bmp = pageBitmap
        if (bmp != null) {
            val rot = (pageState?.rotationDegrees ?: 0) % 360
            val isRotated = rot == 90 || rot == 270
            val bWidth = if (isRotated) bmp.height else bmp.width
            val bHeight = if (isRotated) bmp.width else bmp.height

            // Use parent available width or bitmap width
            val width = resolveSize(bWidth, widthMeasureSpec)
            val scale = width.toFloat() / bWidth.toFloat()
            val height = (bHeight * scale).toInt()
            setMeasuredDimension(width, height)
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = pageBitmap ?: return
        val state = pageState ?: return

        canvas.save()

        // Handle page rotation if any
        val rot = state.rotationDegrees % 360
        if (rot != 0) {
            canvas.rotate(rot.toFloat(), width / 2f, height / 2f)
        }

        // Draw PDF page base
        val destRect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawBitmap(bmp, null, destRect, bitmapPaint)

        // Draw Annotations
        for (item in state.annotations) {
            when (item) {
                is Annotation.WhiteoutItem -> {
                    canvas.drawRect(item.bounds, whiteoutPaint)
                    if (item.isSelected) {
                        canvas.drawRect(item.bounds, selectionPaint)
                        drawResizeHandles(canvas, item.bounds)
                    }
                }
                is Annotation.StrokeItem -> {
                    penPaint.color = item.color
                    penPaint.strokeWidth = item.strokeWidth
                    val path = Path()
                    val pts = item.points
                    if (pts.isNotEmpty()) {
                        path.moveTo(pts[0].x, pts[0].y)
                        for (i in 1 until pts.size) {
                            path.lineTo(pts[i].x, pts[i].y)
                        }
                    }
                    canvas.drawPath(path, penPaint)
                }
                is Annotation.SignatureItem -> {
                    canvas.drawBitmap(item.bitmap, null, item.bounds, bitmapPaint)
                    if (item.isSelected) {
                        canvas.drawRect(item.bounds, selectionPaint)
                        drawResizeHandles(canvas, item.bounds)
                    }
                }
                is Annotation.TextItem -> {
                    textPaint.textSize = item.textSize
                    textPaint.color = item.color
                    canvas.drawText(item.text, item.x, item.y, textPaint)
                    if (item.isSelected) {
                        val textBounds = Rect()
                        textPaint.getTextBounds(item.text, 0, item.text.length, textBounds)
                        val box = RectF(
                            item.x - 4f,
                            item.y + textBounds.top - 4f,
                            item.x + textBounds.width() + 8f,
                            item.y + 4f
                        )
                        canvas.drawRect(box, selectionPaint)
                    }
                }
            }
        }

        // Draw active drawing in progress
        if (activeDragMode == DragMode.DRAWING) {
            if (currentTool == ToolMode.WHITEOUT) {
                canvas.drawRect(activeTempRect, whiteoutPaint)
                canvas.drawRect(activeTempRect, selectionPaint)
            } else if (currentTool == ToolMode.PEN || currentTool == ToolMode.HIGHLIGHT) {
                currentStroke?.let { stroke ->
                    penPaint.color = stroke.color
                    penPaint.strokeWidth = stroke.strokeWidth
                    val path = Path()
                    val pts = stroke.points
                    if (pts.isNotEmpty()) {
                        path.moveTo(pts[0].x, pts[0].y)
                        for (i in 1 until pts.size) {
                            path.lineTo(pts[i].x, pts[i].y)
                        }
                    }
                    canvas.drawPath(path, penPaint)
                }
            }
        }

        canvas.restore()
    }

    private fun drawResizeHandles(canvas: Canvas, r: RectF) {
        val handleRadius = 10f
        // Bottom-Right handle
        canvas.drawRect(r.right - handleRadius, r.bottom - handleRadius, r.right + handleRadius, r.bottom + handleRadius, handlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (currentTool) {
            ToolMode.SELECT -> handleSelectTouch(event, x, y)
            ToolMode.WHITEOUT -> handleWhiteoutTouch(event, x, y)
            ToolMode.PEN, ToolMode.HIGHLIGHT -> handlePenTouch(event, x, y)
            else -> return super.onTouchEvent(event)
        }
        return true
    }

    private fun handleSelectTouch(event: MotionEvent, x: Float, y: Float) {
        val state = pageState ?: return

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = x
                touchStartY = y

                // Check if tapping bottom-right handle of selected item
                val sel = selectedAnnotation
                if (sel is Annotation.SignatureItem && isNearBottomRight(sel.bounds, x, y)) {
                    activeDragMode = DragMode.RESIZE
                    activeCorner = ResizeCorner.BOTTOM_RIGHT
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return
                } else if (sel is Annotation.WhiteoutItem && isNearBottomRight(sel.bounds, x, y)) {
                    activeDragMode = DragMode.RESIZE
                    activeCorner = ResizeCorner.BOTTOM_RIGHT
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return
                }

                // Hit test top-down for selection
                var hitItem: Annotation? = null
                for (i in state.annotations.indices.reversed()) {
                    val item = state.annotations[i]
                    if (isHit(item, x, y)) {
                        hitItem = item
                        break
                    }
                }

                clearSelection()
                if (hitItem != null) {
                    setSelected(hitItem, true)
                    selectedAnnotation = hitItem
                    activeDragMode = DragMode.MOVE
                    parent?.requestDisallowInterceptTouchEvent(true)
                } else {
                    activeDragMode = DragMode.NONE
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = x - touchStartX
                val dy = y - touchStartY
                val sel = selectedAnnotation

                if (activeDragMode == DragMode.MOVE && sel != null) {
                    when (sel) {
                        is Annotation.SignatureItem -> sel.bounds.offset(dx, dy)
                        is Annotation.WhiteoutItem -> sel.bounds.offset(dx, dy)
                        is Annotation.TextItem -> {
                            sel.x += dx
                            sel.y += dy
                        }
                        else -> {}
                    }
                    touchStartX = x
                    touchStartY = y
                    invalidate()
                } else if (activeDragMode == DragMode.RESIZE && sel != null) {
                    when (sel) {
                        is Annotation.SignatureItem -> {
                            sel.bounds.right = max(sel.bounds.left + 30f, sel.bounds.right + dx)
                            sel.bounds.bottom = max(sel.bounds.top + 20f, sel.bounds.bottom + dy)
                        }
                        is Annotation.WhiteoutItem -> {
                            sel.bounds.right = max(sel.bounds.left + 20f, sel.bounds.right + dx)
                            sel.bounds.bottom = max(sel.bounds.top + 10f, sel.bounds.bottom + dy)
                        }
                        else -> {}
                    }
                    touchStartX = x
                    touchStartY = y
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeDragMode = DragMode.NONE
                activeCorner = ResizeCorner.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
    }

    private fun handleWhiteoutTouch(event: MotionEvent, x: Float, y: Float) {
        val state = pageState ?: return

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = x
                touchStartY = y
                activeTempRect.set(x, y, x, y)
                activeDragMode = DragMode.DRAWING
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeDragMode == DragMode.DRAWING) {
                    activeTempRect.left = min(touchStartX, x)
                    activeTempRect.top = min(touchStartY, y)
                    activeTempRect.right = max(touchStartX, x)
                    activeTempRect.bottom = max(touchStartY, y)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (activeDragMode == DragMode.DRAWING && activeTempRect.width() > 10 && activeTempRect.height() > 6) {
                    val whiteout = Annotation.WhiteoutItem(RectF(activeTempRect))
                    state.annotations.add(whiteout)
                    selectedAnnotation = whiteout
                    whiteout.isSelected = true
                    currentTool = ToolMode.SELECT
                }
                activeDragMode = DragMode.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                activeDragMode = DragMode.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
            }
        }
    }

    private fun handlePenTouch(event: MotionEvent, x: Float, y: Float) {
        val state = pageState ?: return

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val isHighlighter = currentTool == ToolMode.HIGHLIGHT
                val stroke = Annotation.StrokeItem(
                    points = mutableListOf(PointF(x, y)),
                    color = if (isHighlighter) highlighterColor else penColor,
                    strokeWidth = if (isHighlighter) highlighterWidth else penWidth,
                    isHighlighter = isHighlighter
                )
                currentStroke = stroke
                activeDragMode = DragMode.DRAWING
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                currentStroke?.points?.add(PointF(x, y))
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                currentStroke?.let { stroke ->
                    if (stroke.points.size > 1) {
                        state.annotations.add(stroke)
                    }
                }
                currentStroke = null
                activeDragMode = DragMode.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                currentStroke = null
                activeDragMode = DragMode.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
            }
        }
    }

    private fun isNearBottomRight(r: RectF, x: Float, y: Float): Boolean {
        val threshold = 30f
        return Math.abs(x - r.right) <= threshold && Math.abs(y - r.bottom) <= threshold
    }

    private fun isHit(item: Annotation, x: Float, y: Float): Boolean {
        return when (item) {
            is Annotation.SignatureItem -> item.bounds.contains(x, y)
            is Annotation.WhiteoutItem -> item.bounds.contains(x, y)
            is Annotation.TextItem -> {
                val textBounds = Rect()
                textPaint.textSize = item.textSize
                textPaint.getTextBounds(item.text, 0, item.text.length, textBounds)
                val box = RectF(
                    item.x - 8f,
                    item.y + textBounds.top - 8f,
                    item.x + textBounds.width() + 16f,
                    item.y + 8f
                )
                box.contains(x, y)
            }
            else -> false
        }
    }

    private fun setSelected(item: Annotation, selected: Boolean) {
        when (item) {
            is Annotation.SignatureItem -> item.isSelected = selected
            is Annotation.WhiteoutItem -> item.isSelected = selected
            is Annotation.TextItem -> item.isSelected = selected
            else -> {}
        }
    }

    private fun clearSelection() {
        pageState?.annotations?.forEach { setSelected(it, false) }
        selectedAnnotation = null
    }

    fun addSignature(bitmap: Bitmap) {
        val state = pageState ?: return
        val w = width.toFloat()
        val h = height.toFloat()

        // Size signature nicely (~40% of page width)
        val sigW = (w * 0.4f)
        val ratio = bitmap.height.toFloat() / bitmap.width.toFloat()
        val sigH = sigW * ratio

        val left = (w - sigW) / 2f
        val top = (h - sigH) / 2f
        val bounds = RectF(left, top, left + sigW, top + sigH)

        val sigItem = Annotation.SignatureItem(bitmap, bounds, isSelected = true)
        clearSelection()
        state.annotations.add(sigItem)
        selectedAnnotation = sigItem
        currentTool = ToolMode.SELECT
        invalidate()
    }

    fun addText(text: String, sizeSp: Float, color: Int) {
        val state = pageState ?: return
        val px = sizeSp * resources.displayMetrics.scaledDensity

        val w = width.toFloat()
        val h = height.toFloat()
        val textItem = Annotation.TextItem(
            text = text,
            x = w * 0.2f,
            y = h * 0.3f,
            textSize = px,
            color = color,
            isSelected = true
        )
        clearSelection()
        state.annotations.add(textItem)
        selectedAnnotation = textItem
        currentTool = ToolMode.SELECT
        invalidate()
    }

    fun undoLastAnnotation(): Boolean {
        val state = pageState ?: return false
        if (state.annotations.isNotEmpty()) {
            state.annotations.removeAt(state.annotations.size - 1)
            selectedAnnotation = null
            invalidate()
            return true
        }
        return false
    }

    fun deleteSelected(): Boolean {
        val state = pageState ?: return false
        val sel = selectedAnnotation ?: return false
        state.annotations.remove(sel)
        selectedAnnotation = null
        invalidate()
        return true
    }

    fun clearAllAnnotations() {
        pageState?.annotations?.clear()
        selectedAnnotation = null
        invalidate()
    }
}
