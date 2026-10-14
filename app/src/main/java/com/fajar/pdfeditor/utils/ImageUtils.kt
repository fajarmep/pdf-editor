package com.fajar.pdfeditor.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min

object ImageUtils {

    /**
     * Decodes a downsampled bitmap from Uri to prevent OOM errors.
     */
    fun decodeSampledBitmapFromUri(context: Context, uri: Uri, maxDim: Int = 1200): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }

            var inSampleSize = 1
            if (options.outHeight > maxDim || options.outWidth > maxDim) {
                val halfHeight: Int = options.outHeight / 2
                val halfWidth: Int = options.outWidth / 2
                while ((halfHeight / inSampleSize) >= maxDim && (halfWidth / inSampleSize) >= maxDim) {
                    inSampleSize *= 2
                }
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Replaces white/near-white background pixels with transparency.
     * Uses fast bulk pixel arrays for high performance on mobile CPUs.
     */
    fun removeWhiteBackground(source: Bitmap, threshold: Int = 230): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        var minX = width
        var minY = height
        var maxX = 0
        var maxY = 0
        var hasVisiblePixel = false

        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                val pixel = pixels[offset + x]
                val a = (pixel shr 24) and 0xFF
                if (a == 0) continue

                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                // Check if color is bright white within threshold
                if (r >= threshold && g >= threshold && b >= threshold) {
                    pixels[offset + x] = 0 // Transparent
                } else {
                    // Update content bounding box for auto-crop
                    hasVisiblePixel = true
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        val resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        resultBitmap.setPixels(pixels, 0, width, 0, 0, width, height)

        // Auto-crop to content bounding box with a 4px safety padding
        return if (hasVisiblePixel && maxX > minX && maxY > minY) {
            val pad = 4
            val cropX = max(0, minX - pad)
            val cropY = max(0, minY - pad)
            val cropW = min(width - cropX, (maxX - minX) + (pad * 2))
            val cropH = min(height - cropY, (maxY - minY) + (pad * 2))
            try {
                Bitmap.createBitmap(resultBitmap, cropX, cropY, cropW, cropH)
            } catch (_: Exception) {
                resultBitmap
            }
        } else {
            resultBitmap
        }
    }
}
