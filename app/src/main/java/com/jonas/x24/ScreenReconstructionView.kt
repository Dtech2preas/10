package com.jonas.x24

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

// -----------------------------
// RAW ELEMENT
// -----------------------------
data class ScreenElement(
    val type: String,
    val label: String,
    val bounds: Rect
)

// -----------------------------
// UI COMPONENTS (NEW LAYER)
// -----------------------------
sealed class UiComponent {
    data class MessageBubble(
        val text: String,
        val time: String?,
        val status: String?,
        val bounds: Rect,
        val isSent: Boolean
    ) : UiComponent()

    data class GenericElement(
        val element: ScreenElement
    ) : UiComponent()
}

// -----------------------------
// VIEW
// -----------------------------
class ScreenReconstructionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var elements: List<ScreenElement> = emptyList()
    private var components: List<UiComponent> = emptyList()

    private var maxRight = 1080
    private var maxBottom = 2400

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setElements(newElements: List<ScreenElement>) {
        elements = newElements

        maxRight = newElements.maxOfOrNull { it.bounds.right } ?: 1080
        maxBottom = newElements.maxOfOrNull { it.bounds.bottom } ?: 2400

        // 🔥 NEW: convert raw → components
        components = parseElements(newElements)

        invalidate()
    }

    // -----------------------------
    // PARSER (PHASE 2)
    // -----------------------------
    private fun parseElements(elements: List<ScreenElement>): List<UiComponent> {

        val result = mutableListOf<UiComponent>()
        val used = mutableSetOf<ScreenElement>()

        for (el in elements) {

            if (used.contains(el)) continue

            // Detect message text
            if (el.type == "Text" && el.label.isNotBlank()) {

                val nearby = elements.filter {
                    it != el &&
                    Math.abs(it.bounds.top - el.bounds.bottom) < 80
                }

                val time = nearby.find { it.label.matches(Regex("\\d{2}:\\d{2}")) }
                val status = nearby.find { it.label in listOf("Read", "Delivered", "Sent") }

                if (time != null) used.add(time)
                if (status != null) used.add(status)

                val isSent = el.bounds.centerX() > maxRight / 2

                result.add(
                    UiComponent.MessageBubble(
                        text = el.label,
                        time = time?.label,
                        status = status?.label,
                        bounds = el.bounds,
                        isSent = isSent
                    )
                )

                used.add(el)
            } else {
                result.add(UiComponent.GenericElement(el))
            }
        }

        return result
    }

    // -----------------------------
    // DRAW
    // -----------------------------
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (components.isEmpty()) {
            paint.color = Color.GRAY
            paint.textSize = 50f
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("No screen data", width / 2f, height / 2f, paint)
            return
        }

        val scaleX = width.toFloat() / maxRight
        val scaleY = height.toFloat() / maxBottom
        val scale = minOf(scaleX, scaleY)

        val offsetX = (width - maxRight * scale) / 2f
        val offsetY = (height - maxBottom * scale) / 2f

        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)

        // Background
        paint.color = Color.parseColor("#ECE5DD")
        canvas.drawRect(0f, 0f, maxRight.toFloat(), maxBottom.toFloat(), paint)

        // Draw components
        for (component in components) {
            when (component) {
                is UiComponent.MessageBubble -> drawMessage(canvas, component)
                is UiComponent.GenericElement -> drawGeneric(canvas, component.element)
            }
        }

        canvas.restore()
    }

    // -----------------------------
    // DRAW MESSAGE (NEW)
    // -----------------------------
    private fun drawMessage(canvas: Canvas, msg: UiComponent.MessageBubble) {

        val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        val rect = RectF(msg.bounds)

        val padding = 20f
        rect.inset(-padding, -padding)

        // Align left/right
        if (msg.isSent) {
            rect.offset((maxRight - rect.right - 40f), 0f)
            bubblePaint.color = Color.parseColor("#DCF8C6") // WhatsApp green
        } else {
            rect.offset((40f - rect.left), 0f)
            bubblePaint.color = Color.WHITE
        }

        // Bubble
        canvas.drawRoundRect(rect, 25f, 25f, bubblePaint)

        // Text
        textPaint.color = Color.BLACK
        textPaint.textSize = 32f

        canvas.drawText(
            msg.text,
            rect.left + 20f,
            rect.centerY(),
            textPaint
        )

        // Time + status
        msg.time?.let {
            textPaint.textSize = 22f
            textPaint.color = Color.DKGRAY

            canvas.drawText(
                it + (msg.status?.let { s -> "  $s" } ?: ""),
                rect.right - 150f,
                rect.bottom - 10f,
                textPaint
            )
        }
    }

    // -----------------------------
    // DRAW GENERIC (fallback)
    // -----------------------------
    private fun drawGeneric(canvas: Canvas, el: ScreenElement) {

        val rect = RectF(el.bounds)

        paint.style = Paint.Style.FILL

        paint.color = when (el.type) {
            "Button" -> Color.parseColor("#BBDEFB")
            "Input" -> Color.parseColor("#FFF9C4")
            "Scrollable" -> Color.parseColor("#F3E5F5")
            else -> Color.WHITE
        }

        canvas.drawRect(rect, paint)

        paint.style = Paint.Style.STROKE
        paint.color = Color.DKGRAY
        canvas.drawRect(rect, paint)

        // Text
        paint.style = Paint.Style.FILL
        paint.color = Color.BLACK
        paint.textSize = 28f

        canvas.drawText(
            el.label,
            rect.centerX(),
            rect.centerY(),
            paint
        )
    }
}