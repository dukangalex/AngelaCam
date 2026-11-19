package com.dukangalex.angelacam

import android.content.Intent
import androidx.core.net.toUri

/** Builds a system chooser only for validated, canonical HTTP(S) URLs. */
internal object QrBrowserIntentFactory {
    fun createChooserIntent(rawText: String, chooserTitle: CharSequence): Intent? {
        val url = QrCodeResultHandler.browserUrlOrNull(rawText) ?: return null
        val viewIntent = Intent(Intent.ACTION_VIEW)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .setDataAndNormalize(url.toUri())
        return Intent.createChooser(viewIntent, chooserTitle)
    }
}
