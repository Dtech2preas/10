package com.jonas.x24

import android.content.Context
import android.graphics.*
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

sealed class UiComponent {
    data class MessageBubble(
        val text: String,
        val isSent: Boolean
    ) : UiComponent()

    data class Header(val text: String) : UiComponent()
}

class ScreenReconstructionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var components: List<UiComponent> = emptyList()

    fun setElements(elements: List<ScreenElement>) {
        components = parse(elements)
        invalidate()
    }

    // -----------------------------
    // SMART PARSER
    // -----------------------------
    private fun parse(elements: List<ScreenElement>): List<UiComponent> {
        val result = mutableListOf<UiComponent>()

        for (el in elements) {

            if (el.type == "Text") {

                val text = el.label.trim()

                if (text.isBlank()) continue

                // Detect header
                if (text.contains("WA Business", true)) {
                    result.add(UiComponent.Header(text))
                    continue
                }

                // Detect messages
                if (text.length > 2) {
                    val isSent = el.bounds.centerX() > 500
                    result.add(
                        UiComponent.MessageBubble(
                            text = text,
                            isSent = isSent
                        )
                    )
                }
            }
        }

        return result
    }

    // -----------------------------
    // DRAW
    // -----------------------------
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val bgPaint = Paint()
        bgPaint.color = Color.parseColor("#ECE5DD")
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        var yOffset = 60f

        for (component in components) {

            when (component) {

                is UiComponent.Header -> {
                    yOffset = drawHeader(canvas, component.text, yOffset)
                }

                is UiComponent.MessageBubble -> {
                    yOffset = drawMessage(canvas, component, yOffset)
                }
            }

            yOffset += 20f // spacing
        }
    }

    // -----------------------------
    // HEADER
    // -----------------------------
    private fun drawHeader(canvas: Canvas, text: String, y: Float): Float {

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE

        val rect = RectF(40f, y, width - 40f, y + 120f)
        canvas.drawRoundRect(rect, 40f, 40f, paint)

        paint.color = Color.BLACK
        paint.textSize = 40f

        canvas.drawText(text, rect.left + 30f, rect.centerY(), paint)

        return rect.bottom
    }

    // -----------------------------
    // MESSAGE DRAW (FIXED 🔥)
    // -----------------------------
    private fun drawMessage(
        canvas: Canvas,
        msg: UiComponent.MessageBubble,
        yStart: Float
    ): Float {

        val maxWidth = width * 0.7f

        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        textPaint.color = Color.BLACK
        textPaint.textSize = 36f

        val staticLayout = StaticLayout.Builder
            .obtain(msg.text, 0, msg.text.length, textPaint, maxWidth.toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.2f)
            .setIncludePad(false)
            .build()

        val bubbleWidth = staticLayout.width + 60f
        val bubbleHeight = staticLayout.height + 40f

        val left = if (msg.isSent) {
            width - bubbleWidth - 40f
        } else {
            40f
        }

        val rect = RectF(
            left,
            yStart,
            left + bubbleWidth,
            yStart + bubbleHeight
        )

        val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        bubblePaint.color = if (msg.isSent)
            Color.parseColor("#DCF8C6")
        else
            Color.WHITE

        canvas.drawRoundRect(rect, 30f, 30f, bubblePaint)

        canvas.save()
        canvas.translate(rect.left + 30f, rect.top + 20f)
        staticLayout.draw(canvas)
        canvas.restore()

        return rect.bottom
    }
}