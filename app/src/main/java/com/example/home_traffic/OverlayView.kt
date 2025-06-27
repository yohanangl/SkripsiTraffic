package com.example.home_traffic

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.example.home_traffic.data.Detection

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var detectionResults: List<Detection> = emptyList()
    private var imageWidth: Int = 1
    private var imageHeight: Int = 1

    private val boxPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }

    private val textBackgroundPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        textSize = 50f
    }

    fun setResults(detections: List<Detection>, sourceImageWidth: Int, sourceImageHeight: Int) {
        detectionResults = detections
        imageWidth = sourceImageWidth
        imageHeight = sourceImageHeight
        invalidate() // Meminta view untuk digambar ulang
    }

    fun clear() {
        detectionResults = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (detectionResults.isEmpty()) return

        val scaleX = width.toFloat() / imageWidth
        val scaleY = height.toFloat() / imageHeight

        for (detection in detectionResults) {
            val scaledRect = RectF(
                detection.box[0] * scaleX,
                detection.box[1] * scaleY,
                detection.box[2] * scaleX,
                detection.box[3] * scaleY
            )

            canvas.drawRect(scaledRect, boxPaint)

            val label = "${detection.class_name} (${String.format("%.2f", detection.confidence)})"
            val textBounds = android.graphics.Rect()
            textPaint.getTextBounds(label, 0, label.length, textBounds)
            val textHeight = textBounds.height()

            canvas.drawRect(
                scaledRect.left,
                scaledRect.top - textHeight - 10,
                scaledRect.left + textPaint.measureText(label) + 10,
                scaledRect.top,
                textBackgroundPaint
            )

            canvas.drawText(
                label,
                scaledRect.left + 5,
                scaledRect.top - 5,
                textPaint
            )
        }
    }
}