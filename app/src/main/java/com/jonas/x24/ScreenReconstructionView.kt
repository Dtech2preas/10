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

    // Hardcoded expected device dimensions (we can estimate if we don't know it,
    // but usually coordinates are in physical pixels, e.g. 1080x2400)
    // We will dynamically calculate max bounds based on input if needed.
    private var maxRight = 1080
    private var maxBottom = 2400

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.DKGRAY
        strokeWidth = 2f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 30f // Will be scaled
        textAlign = Paint.Align.CENTER
    }

    fun setElements(newElements: List<ScreenElement>) {
        this.elements = newElements

        // Auto-detect max bounds to scale appropriately
        maxRight = newElements.maxOfOrNull { it.bounds.right }?.coerceAtLeast(1080) ?: 1080
        maxBottom = newElements.maxOfOrNull { it.bounds.bottom }?.coerceAtLeast(2400) ?: 2400

        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (elements.isEmpty()) {
            val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.GRAY
                textSize = 50f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("No screen data", width / 2f, height / 2f, emptyPaint)
            return
        }

        // Calculate scaling factors so the reconstructed screen fits perfectly in the view
        val scaleX = width.toFloat() / maxRight.toFloat()
        val scaleY = height.toFloat() / maxBottom.toFloat()

        // Maintain aspect ratio, center it if needed, or just fill. Let's maintain aspect ratio.
        val scale = minOf(scaleX, scaleY)

        val offsetX = (width - (maxRight * scale)) / 2f
        val offsetY = (height - (maxBottom * scale)) / 2f

        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)

        // Draw background phone screen
        val bgPaint = Paint().apply { color = Color.parseColor("#f0f0f0") }
        canvas.drawRect(0f, 0f, maxRight.toFloat(), maxBottom.toFloat(), bgPaint)

        // Draw outline of the screen
        val screenOutlinePaint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 5f
        }
        canvas.drawRect(0f, 0f, maxRight.toFloat(), maxBottom.toFloat(), screenOutlinePaint)

        // Draw elements back-to-front (though order in list usually top-to-bottom)
        for (el in elements) {
            val rectF = RectF(el.bounds)

            // Set color based on type
            when (el.type) {
                "Button" -> boxPaint.color = Color.parseColor("#bbdefb") // Light blue
                "Input" -> boxPaint.color = Color.parseColor("#fff9c4")  // Light yellow
                "Scrollable" -> boxPaint.color = Color.parseColor("#f3e5f5") // Light purple
                "Text" -> boxPaint.color = Color.WHITE
                else -> boxPaint.color = Color.WHITE
            }

            canvas.drawRect(rectF, boxPaint)
            canvas.drawRect(rectF, borderPaint)

            // Use TextPaint for StaticLayout
            val textPaintForLayout = TextPaint(textPaint).apply {
                // Adaptive text size
                textSize = (rectF.height() * 0.4f).coerceIn(20f, 60f)
            }

            // We need a width > 0 for StaticLayout
            val layoutWidth = maxOf(rectF.width().toInt() - 8, 1)

            val staticLayout = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                StaticLayout.Builder.obtain(el.label, 0, el.label.length, textPaintForLayout, layoutWidth)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setLineSpacing(0f, 1f)
                    .setIncludePad(false)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                StaticLayout(
                    el.label, textPaintForLayout, layoutWidth,
                    Layout.Alignment.ALIGN_CENTER, 1.0f, 0.0f, false
                )
            }

            // Center the layout vertically and horizontally
            val layoutHeight = staticLayout.height
            val dx = rectF.left + (rectF.width() - layoutWidth) / 2f
            val dy = rectF.top + (rectF.height() - layoutHeight) / 2f

            canvas.save()
            canvas.clipRect(rectF) // Ensure it doesn't spill over
            canvas.translate(dx, dy)
            staticLayout.draw(canvas)
            canvas.restore()
        }

        canvas.restore()
    }
}
