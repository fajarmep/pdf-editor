package com.fajar.pdfeditor.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.fajar.pdfeditor.model.Annotation
import com.fajar.pdfeditor.model.PageState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import kotlin.math.roundToInt

class PdfEngine(private val context: Context) {

    private var fileDescriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    var pageStates: MutableList<PageState> = mutableListOf()
        private set

    var totalPages: Int = 0
        private set

    suspend fun open(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        close()
        try {
            // Copy to local app cache so ParcelFileDescriptor is always seekable
            val tempFile = File(context.cacheDir, "current_editing.pdf")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext false

            val pfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
            fileDescriptor = pfd
            val r = PdfRenderer(pfd)
            renderer = r
            totalPages = r.pageCount

            pageStates = MutableList(totalPages) { index ->
                PageState(originalPageIndex = index)
            }
            true
        } catch (_: Exception) {
            close()
            false
        }
    }

    suspend fun renderPageBitmap(pageIndex: Int, targetWidth: Int = 1080): Bitmap? = withContext(Dispatchers.IO) {
        val r = renderer ?: return@withContext null
        if (pageIndex < 0 || pageIndex >= totalPages) return@withContext null

        try {
            val page = r.openPage(pageIndex)
            val pWidth = page.width
            val pHeight = page.height

            // Calculate scaled dimensions while preserving aspect ratio
            val scale = targetWidth.toFloat() / pWidth.toFloat()
            val renderWidth = targetWidth
            val renderHeight = (pHeight * scale).roundToInt()

            val bitmap = Bitmap.createBitmap(renderWidth, renderHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE) // PDF backgrounds are transparent by default

            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            bitmap
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Exports the edited PDF, combining base pages with all annotations and modifications.
     */
    suspend fun exportPdf(
        outputStream: OutputStream,
        previewWidth: Float,
        previewHeight: Float,
        onProgress: (current: Int, total: Int) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val r = renderer ?: return@withContext false
        val doc = PdfDocument()

        val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        val penPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        val whiteoutPaint = Paint().apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }
        val textPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
        }

        val activePages = pageStates.filter { !it.isDeleted }
        val totalActive = activePages.size

        try {
            var pageCounter = 1
            for (state in activePages) {
                onProgress(pageCounter, totalActive)
                val originalIndex = state.originalPageIndex
                val rendererPage = r.openPage(originalIndex)

                val baseW = rendererPage.width
                val baseH = rendererPage.height

                val rot = state.rotationDegrees % 360
                val isRotated = rot == 90 || rot == 270

                val pageW = if (isRotated) baseH else baseW
                val pageH = if (isRotated) baseW else baseH

                val pageInfo = PdfDocument.PageInfo.Builder(pageW, pageH, pageCounter).create()
                val pdfPage = doc.startPage(pageInfo)
                val canvas = pdfPage.canvas

                // Render high-res base PDF bitmap
                val baseBitmap = Bitmap.createBitmap(baseW, baseH, Bitmap.Config.ARGB_8888)
                val baseCanvas = Canvas(baseBitmap)
                baseCanvas.drawColor(Color.WHITE)
                rendererPage.render(baseBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                rendererPage.close()

                canvas.save()
                if (rot != 0) {
                    canvas.rotate(rot.toFloat(), pageW / 2f, pageH / 2f)
                }

                // Draw base page
                canvas.drawBitmap(baseBitmap, null, RectF(0f, 0f, pageW.toFloat(), pageH.toFloat()), bitmapPaint)
                baseBitmap.recycle()

                // Calculate coordinate scale from Preview View to PDF Page points
                val scaleX = pageW.toFloat() / previewWidth
                val scaleY = pageH.toFloat() / previewHeight

                // Draw annotations
                for (item in state.annotations) {
                    when (item) {
                        is Annotation.WhiteoutItem -> {
                            val scaledBounds = RectF(
                                item.bounds.left * scaleX,
                                item.bounds.top * scaleY,
                                item.bounds.right * scaleX,
                                item.bounds.bottom * scaleY
                            )
                            canvas.drawRect(scaledBounds, whiteoutPaint)
                        }
                        is Annotation.StrokeItem -> {
                            penPaint.color = item.color
                            penPaint.strokeWidth = item.strokeWidth * scaleX
                            val path = Path()
                            val pts = item.points
                            if (pts.isNotEmpty()) {
                                path.moveTo(pts[0].x * scaleX, pts[0].y * scaleY)
                                for (i in 1 until pts.size) {
                                    path.lineTo(pts[i].x * scaleX, pts[i].y * scaleY)
                                }
                            }
                            canvas.drawPath(path, penPaint)
                        }
                        is Annotation.SignatureItem -> {
                            val scaledBounds = RectF(
                                item.bounds.left * scaleX,
                                item.bounds.top * scaleY,
                                item.bounds.right * scaleX,
                                item.bounds.bottom * scaleY
                            )
                            canvas.drawBitmap(item.bitmap, null, scaledBounds, bitmapPaint)
                        }
                        is Annotation.TextItem -> {
                            textPaint.textSize = item.textSize * scaleY
                            textPaint.color = item.color
                            canvas.drawText(item.text, item.x * scaleX, item.y * scaleY, textPaint)
                        }
                    }
                }

                canvas.restore()
                doc.finishPage(pdfPage)
                pageCounter++
            }

            doc.writeTo(outputStream)
            outputStream.flush()
            doc.close()
            true
        } catch (_: Exception) {
            try { doc.close() } catch (_: Exception) {}
            false
        }
    }

    fun close() {
        try { renderer?.close() } catch (_: Exception) {}
        try { fileDescriptor?.close() } catch (_: Exception) {}
        renderer = null
        fileDescriptor = null
        pageStates.clear()
        totalPages = 0
    }
}
