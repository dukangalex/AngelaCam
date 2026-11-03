package com.dukangalex.angelacam

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Applies the system-bar bottom inset without accumulating padding on redispatch. */
internal object NavigationBarInsets {
    fun applyTo(root: View) {
        val initialLeft = root.paddingLeft
        val initialTop = root.paddingTop
        val initialRight = root.paddingRight
        val initialBottom = root.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemBarsBottom =
                insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            view.setPadding(
                initialLeft,
                initialTop,
                initialRight,
                initialBottom + systemBarsBottom,
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }
}
