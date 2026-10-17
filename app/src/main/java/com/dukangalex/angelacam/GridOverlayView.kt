package com.dukangalex.angelacam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** 构图辅助线：关闭 / 九宫格 / 十字 */
class GridOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        const val MODE_OFF = 0
        const val MODE_THIRDS = 1
        const val MODE_CROSS = 2
    }

    var mode: Int = MODE_OFF
        set(value) {
            field = value
            visibility = if (value == MODE_OFF) View.GONE else View.VISIBLE
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = 110
        strokeWidth = 2f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return
        when (mode) {
            MODE_THIRDS -> {
                for (i in 1..2) {
                    canvas.drawLine(w * i / 3f, 0f, w * i / 3f, h, paint)
                    canvas.drawLine(0f, h * i / 3f, w, h * i / 3f, paint)
                }
            }
            MODE_CROSS -> {
                canvas.drawLine(w / 2f, 0f, w / 2f, h, paint)
                canvas.drawLine(0f, h / 2f, w, h / 2f, paint)
            }
        }
    }
}
