package com.dukangalex.angelacam

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.TextPaint
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CameraModeAppearanceTest {
    @Test
    fun themedLayoutKeepsPhotoAndVideoLabelsAndBackgroundsHighContrast() {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(R.style.Theme_AngelaCam)
        val activity = controller.setup().get()
        activity.setContentView(R.layout.activity_main)

        val photo = activity.findViewById<Button>(R.id.btnModePhoto)
        val video = activity.findViewById<Button>(R.id.btnModeVideo)
        val selector = activity.findViewById<LinearLayout>(R.id.modeSelector)
        photo.isSelected = true
        video.isSelected = false

        val selectedGreen = Color.parseColor("#FF63FF9B")
        val selectedBackground = Color.parseColor("#FF103522")
        val unselectedBackgroundOnBlack = Color.rgb(0x24, 0x24, 0x24)
        assertEquals("selected photo label uses vivid green", selectedGreen, photo.currentTextColor)
        assertEquals("unselected video label remains white", Color.WHITE, video.currentTextColor)

        selector.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        selector.layout(0, 0, selector.measuredWidth, selector.measuredHeight)
        val photoBg = sampleActualBackground(photo)
        val videoBg = sampleActualBackground(video)
        assertEquals(
            "selected photo background should be the intended dark-green chip; ${backgroundDetails(photo, photoBg)}",
            selectedBackground,
            photoBg
        )
        assertEquals(
            "unselected video background should remain a light glass chip; ${backgroundDetails(video, videoBg)}",
            unselectedBackgroundOnBlack,
            videoBg
        )
        assertTrue("white video label has readable contrast on its inflated background", contrastRatio(Color.WHITE, videoBg) >= 4.5)
        assertTrue("green photo label has readable contrast on its inflated background", contrastRatio(photo.currentTextColor, photoBg) >= 4.5)
        val photoSelectedScreenshot = renderSelector(selector)

        photo.isSelected = false
        video.isSelected = true
        assertEquals("unselected photo label is white", Color.WHITE, photo.currentTextColor)
        assertEquals("selected video label uses vivid green", selectedGreen, video.currentTextColor)
        val unselectedPhotoBg = sampleActualBackground(photo)
        val selectedVideoBg = sampleActualBackground(video)
        assertEquals("video selected state uses the same explicit dark-green chip", selectedBackground, selectedVideoBg)
        assertEquals("photo unselected state returns to the light glass chip", unselectedBackgroundOnBlack, unselectedPhotoBg)
        assertTrue("green video label has readable contrast on its inflated background", contrastRatio(video.currentTextColor, selectedVideoBg) >= 4.5)
        assertTrue("white photo label has readable contrast on its inflated background", contrastRatio(Color.WHITE, unselectedPhotoBg) >= 4.5)
        writeScreenshot(photoSelectedScreenshot, renderSelector(selector))
    }

    private fun sampleActualBackground(button: Button): Int {
        val width = button.width.coerceAtLeast(1)
        val height = button.height.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)
        val background = button.background ?: return Color.TRANSPARENT
        background.state = button.drawableState
        background.setBounds(0, 0, width, height)
        background.draw(canvas)
        return bitmap.getPixel(width / 2, height / 2)
    }

    private fun backgroundDetails(button: Button, actual: Int): String {
        val tint = button.backgroundTintList?.getColorForState(button.drawableState, Color.TRANSPARENT)
            ?: Color.TRANSPARENT
        val current = button.background?.current
        val currentColor = (current as? android.graphics.drawable.GradientDrawable)?.color
            ?.getColorForState(button.drawableState, Color.TRANSPARENT) ?: Color.TRANSPARENT
        val raw = button.resources.getDrawable(R.drawable.bg_camera_mode_chip, button.context.theme)
        raw.state = button.drawableState
        val rawPixel = sampleDrawable(raw, button.width, button.height)
        return "actual=${String.format("#%08X", actual)}, drawable=${button.background?.javaClass?.name}, " +
            "current=${current?.javaClass?.name}, currentColor=${String.format("#%08X", currentColor)}, " +
            "resourcePixel=${String.format("#%08X", rawPixel)}, tint=${String.format("#%08X", tint)}, " +
            "state=${button.drawableState.contentToString()}, size=${button.width}x${button.height}"
    }

    private fun sampleDrawable(drawable: android.graphics.drawable.Drawable, width: Int, height: Int): Int {
        val bitmap = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)
        drawable.setBounds(0, 0, bitmap.width, bitmap.height)
        drawable.draw(canvas)
        return bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
    }

    private fun renderSelector(selector: LinearLayout): Bitmap {
        val scale = 3
        val bitmap = Bitmap.createBitmap(selector.width * scale, selector.height * scale, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)
        canvas.scale(scale.toFloat(), scale.toFloat())
        selector.draw(canvas)
        // Robolectric draws the actual button backgrounds but omits TextView glyphs from this off-screen capture.
        // Use each inflated Button's own text, typeface, size and currentTextColor for the screenshot labels.
        for (index in 0 until selector.childCount) {
            val button = selector.getChildAt(index) as? Button ?: continue
            val text = button.text?.toString().orEmpty()
            if (text.isEmpty()) continue
            val paint = TextPaint(button.paint).apply {
                color = button.currentTextColor
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
            }
            val metrics = paint.fontMetrics
            val baseline = button.top + button.height / 2f - (metrics.ascent + metrics.descent) / 2f
            canvas.drawText(text, button.left + button.width / 2f, baseline, paint)
        }
        return bitmap
    }

    private fun writeScreenshot(photoSelected: Bitmap, videoSelected: Bitmap) {
        val gap = 12
        val combined = Bitmap.createBitmap(
            maxOf(photoSelected.width, videoSelected.width),
            photoSelected.height + gap + videoSelected.height,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(combined)
        canvas.drawColor(Color.rgb(12, 14, 18))
        canvas.drawBitmap(photoSelected, 0f, 0f, null)
        canvas.drawBitmap(videoSelected, 0f, (photoSelected.height + gap).toFloat(), null)
        val file = File("build/reports/camera-mode-selector.png")
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { combined.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun contrastRatio(foreground: Int, background: Int): Double {
        fun luminance(color: Int): Double {
            fun linear(channel: Int): Double {
                val value = channel / 255.0
                return if (value <= 0.04045) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * linear(Color.red(color)) + 0.7152 * linear(Color.green(color)) + 0.0722 * linear(Color.blue(color))
        }
        val first = luminance(foreground)
        val second = luminance(background)
        return (maxOf(first, second) + 0.05) / (minOf(first, second) + 0.05)
    }
}
