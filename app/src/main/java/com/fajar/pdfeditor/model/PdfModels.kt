package com.fajar.pdfeditor.model

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF

enum class ToolMode {
    SELECT,
    SIGNATURE,
    TEXT,
    WHITEOUT,
    PEN,
    HIGHLIGHT
}

sealed class Annotation {
    data class SignatureItem(
        var bitmap: Bitmap,
        var bounds: RectF,
        var isSelected: Boolean = false
    ) : Annotation()

    data class TextItem(
        var text: String,
        var x: Float,
        var y: Float,
        var textSize: Float,
        var color: Int,
        var isSelected: Boolean = false
    ) : Annotation()

    data class WhiteoutItem(
        var bounds: RectF,
        var isSelected: Boolean = false
    ) : Annotation()

    data class StrokeItem(
        val points: MutableList<PointF>,
        val color: Int,
        val strokeWidth: Float,
        val isHighlighter: Boolean
    ) : Annotation()
}

data class PageState(
    val originalPageIndex: Int,
    var rotationDegrees: Int = 0,
    var isDeleted: Boolean = false,
    val annotations: MutableList<Annotation> = mutableListOf()
)
