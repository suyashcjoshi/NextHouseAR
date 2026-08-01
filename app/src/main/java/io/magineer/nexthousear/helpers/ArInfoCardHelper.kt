/* Copyright 2026 Suyash Joshi */
package io.magineer.nexthousear.helpers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

object ArInfoCardHelper {

    /**
     * Creates a bitmap containing location information to be used as a 3D AR texture.
     */
    fun createInfoCardBitmap(
        title: String,
        lines: List<String>
    ): Bitmap {
        val width = 768
        val height = 384
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val paint = Paint().apply {
            color = Color.argb(220, 28, 31, 34)
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 28f, 28f, paint)

        paint.apply {
            color = Color.argb(235, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawRoundRect(2f, 2f, (width - 2).toFloat(), (height - 2).toFloat(), 28f, 28f, paint)

        val titlePaint = Paint().apply {
            color = Color.WHITE
            textSize = 42f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }
        val detailPaint = Paint().apply {
            color = Color.rgb(222, 226, 230)
            textSize = 32f
            typeface = Typeface.DEFAULT
            isAntiAlias = true
        }

        canvas.drawText(ellipsize(title.ifBlank { "Selected location" }, titlePaint, width - 56f), 28f, 58f, titlePaint)

        var yPos = 112f
        lines.take(7).forEach { line ->
            canvas.drawText(ellipsize(line, detailPaint, width - 56f), 28f, yPos, detailPaint)
            yPos += 42f
        }

        return bitmap
    }

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var trimmed = text
        while (trimmed.isNotEmpty() && paint.measureText("$trimmed...") > maxWidth) {
            trimmed = trimmed.dropLast(1)
        }
        return "$trimmed..."
    }
}
