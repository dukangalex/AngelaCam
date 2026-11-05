package com.dukangalex.angelacam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import android.widget.ImageView
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.abs
import kotlin.math.min

/** A ViewPager-friendly image view: pinch to zoom, then drag to inspect the photo. */
class ZoomImageView(context: Context) : AppCompatImageView(context) {
    companion object {
        private const val MIN_SCALE = 1f
        private const val MAX_SCALE = 5f
    }

    private data class Fit(val scale: Float, val left: Float, val top: Float, val width: Float, val height: Float)

    private val drawMatrix = Matrix()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var zoomScale = MIN_SCALE
    private var offsetX = 0f
    private var offsetY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val fit = fitGeometry() ?: return false
            val oldScale = zoomScale
            val newScale = (oldScale * detector.scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
            val appliedFactor = newScale / oldScale
            if (appliedFactor != 1f) {
                offsetX = ImageZoomMath.offsetAfterZoom(fit.left, offsetX, detector.focusX, appliedFactor)
                offsetY = ImageZoomMath.offsetAfterZoom(fit.top, offsetY, detector.focusY, appliedFactor)
                zoomScale = newScale
                clampOffsets(fit)
                applyImageMatrix(fit)
            }
            return true
        }
    })

    init {
        scaleType = ImageView.ScaleType.MATRIX
        isClickable = true
    }

    /** Replaces the displayed bitmap and resets any zoom/pan from the previous page. */
    fun displayBitmap(bitmap: Bitmap?) {
        setImageBitmap(bitmap)
        zoomScale = MIN_SCALE
        offsetX = 0f
        offsetY = 0f
        fitGeometry()?.let(::applyImageMatrix)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        zoomScale = MIN_SCALE
        offsetX = 0f
        offsetY = 0f
        fitGeometry()?.let(::applyImageMatrix)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                moved = false
                if (zoomScale > MIN_SCALE) parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                moved = true
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1 && !scaleDetector.isInProgress && zoomScale > MIN_SCALE) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) moved = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    offsetX += dx
                    offsetY += dy
                    lastX = event.x
                    lastY = event.y
                    fitGeometry()?.let { fit ->
                        clampOffsets(fit)
                        applyImageMatrix(fit)
                    }
                } else if (event.pointerCount > 1) {
                    moved = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val remaining = if (event.actionIndex == 0) 1 else 0
                if (remaining < event.pointerCount) {
                    lastX = event.getX(remaining)
                    lastY = event.getY(remaining)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (zoomScale <= MIN_SCALE) {
                    offsetX = 0f
                    offsetY = 0f
                    fitGeometry()?.let(::applyImageMatrix)
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                if (!moved) performClick()
            }
            MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun fitGeometry(): Fit? {
        val d = drawable ?: return null
        if (width <= 0 || height <= 0 || d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0) return null
        val fitScale = min(width.toFloat() / d.intrinsicWidth, height.toFloat() / d.intrinsicHeight)
        val fittedWidth = d.intrinsicWidth * fitScale
        val fittedHeight = d.intrinsicHeight * fitScale
        return Fit(fitScale, (width - fittedWidth) / 2f, (height - fittedHeight) / 2f, fittedWidth, fittedHeight)
    }

    private fun clampOffsets(fit: Fit) {
        offsetX = ImageZoomMath.clampOffset(offsetX, width.toFloat(), fit.width, fit.left, zoomScale)
        offsetY = ImageZoomMath.clampOffset(offsetY, height.toFloat(), fit.height, fit.top, zoomScale)
    }

    private fun applyImageMatrix(fit: Fit) {
        if (drawable == null) return
        drawMatrix.reset()
        drawMatrix.setScale(fit.scale * zoomScale, fit.scale * zoomScale)
        drawMatrix.postTranslate(fit.left + offsetX, fit.top + offsetY)
        imageMatrix = drawMatrix
        invalidate()
    }
}
