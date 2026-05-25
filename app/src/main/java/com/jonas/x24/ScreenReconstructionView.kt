package com.jonas.x24

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View

data class ScreenElement(
    val type: String,
    val label: String,
    val bounds: Rect
)

class ScreenReconstructionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var elements: List<ScreenElement> = emptyList()

    private var maxRight = 1080
    private var maxBottom = 2400

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#CCCCCC")
        strokeWidth = 1.5f
    }

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#212121")
    }

    fun setElements(newElements: List<ScreenElement>) {
        // CRITICAL STEP: Sort by area size descending.
        // This ensures massive layout containers (Scrollables) are drawn *first* in the background,
        // and tiny text elements/buttons are drawn *on top* of them.
        this.elements = newElements.sortedByDescending { it.bounds.width() * it.bounds.height() }

        maxRight = newElements.maxOfOrNull { it.bounds.right }?.coerceAtLeast(1080) ?: 1080
        maxBottom = newElements.maxOfOrNull { it.bounds.bottom }?.coerceAtLeast(2400) ?: 2400

        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (elements.isEmpty()) {
            val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.GRAY
                textSize = 40f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("No screen data loaded", width / 2f, height / 2f, emptyPaint)
            return
        }

        // Handle scaling to strictly fill the view's dimensions
        val scaleX = width.toFloat() / maxRight.toFloat()
        val scaleY = height.toFloat() / maxBottom.toFloat()

        canvas.save()
        canvas.scale(scaleX, scaleY)

        // Draw Device Canvas Background
        fillPaint.color = Color.parseColor("#F5F5F7")
        canvas.drawRect(0f, 0f, maxRight.toFloat(), maxBottom.toFloat(), fillPaint)

        // Render each element safely
        for (el in elements) {
            val rectF = RectF(el.bounds)
            
            // Ignore completely collapsed elements
            if (rectF.width() <= 0 || rectF.height() <= 0) continue

            // 1. Assign professional, semi-translucent colors so nested objects show through
            when (el.type) {
                "Scrollable" -> fillPaint.color = Color.parseColor("#F3E5F5") // Light elegant purple
                "Button" -> fillPaint.color = Color.parseColor("#E3F2FD")     // Clean soft blue
                "Input" -> fillPaint.color = Color.parseColor("#FFFDE7")      // Pastel yellow
                "Text" -> fillPaint.color = Color.WHITE
                else -> fillPaint.color = Color.parseColor("#FAFAFA")
            }

            // Draw element body and its bounding border
            canvas.drawRect(rectF, fillPaint)
            canvas.drawRect(rectF, borderPaint)

            // 2. Handle text wrapping safely inside its specific bounding box
            if (el.label.isNotBlank()) {
                canvas.save()
                // Add a small 4px padding inside the boundary box so text doesn't touch edges
                val padding = 4f
                val innerWidth = (rectF.width() - (padding * 2)).coerceAtLeast(10f)
                val innerHeight = (rectF.height() - (padding * 2)).coerceAtLeast(10f)

                canvas.clipRect(rectF) // Prevent any leaks outside its zone
                canvas.translate(rectF.left + padding, rectF.top + padding)

                // Mathematically calculate a pleasant text size that scales with bounding box height
                val computedTextSize = (rectF.height() * 0.35f).coerceIn(14f, 28f)
                textPaint.textSize = computedTextSize

                // Create a wrapping text engine layout
                val alignment = if (el.type == "Button" || el.type == "Input") {
                    Layout.Alignment.ALIGN_CENTER
                } else {
                    Layout.Alignment.ALIGN_NORMAL
                }

                val staticLayout = StaticLayout.Builder.obtain(
                    el.label, 0, el.label.length, textPaint, innerWidth.toInt()
                )
                    .setAlignment(alignment)
                    .setLineSpacing(0f, 1.0f)
                    .setIncludePad(false)
                    .setMaxLines((innerHeight / textPaint.fontSpacing).toInt().coerceAtLeast(1))
                    .setEllipsize(android.text.TextUtils.TruncateAt.END) // Graceful truncate with "..."
                    .build()

                // Center vertical alignment inside smaller boxes
                if (staticLayout.height < innerHeight) {
                    val verticalOffset = (innerHeight - staticLayout.height) / 2f
                    canvas.translate(0f, verticalOffset)
                }

                staticLayout.draw(canvas)
                canvas.restore()
            }
        }

        // Draw crisp Device Boundary Edge
        val outerFramePaint = Paint().apply {
            color = Color.parseColor("#9E9E9E")
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        canvas.drawRect(0f, 0f, maxRight.toFloat(), maxBottom.toFloat(), outerFramePaint)

        canvas.restore()
    }
}
