package com.dukangalex.angelacam

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class QrBrowserIntentFactoryTest {
    @Test
    fun wrapsValidHttpUrlInSystemChooser() {
        assertChooser("http://example.com:8080/path?q=1")
    }

    @Test
    fun wrapsValidHttpsUrlInSystemChooser() {
        assertChooser("https://example.com/path?q=1")
    }

    @Test
    fun normalizesUppercaseSchemeAndHostBeforeCreatingChooser() {
        val raw = " HTTPS://Example.COM/Path?X=Ab#Frag "
        val canonical = "https://example.com/Path?X=Ab#Frag"
        assertEquals(canonical, QrCodeResultHandler.browserUrlOrNull(raw))
        assertChooser(raw, canonical)
    }

    @Test
    fun normalizesMixedCaseHttpSchemeAndHost() {
        assertEquals(
            "http://example.com/path",
            QrCodeResultHandler.browserUrlOrNull("HtTp://Example.COM/path")
        )
    }

    @Test
    fun rejectsUnsupportedSchemesAndUrlsWithoutAValidHost() {
        listOf(
            "plain text",
            "ftp://example.com/file",
            "javascript:alert(1)",
            "https://",
            "https://bad host/path",
            "https://example.com:99999/path"
        ).forEach { raw ->
            assertNull(raw, QrBrowserIntentFactory.createChooserIntent(raw, CHOOSER_TITLE))
        }
    }

    private fun assertChooser(url: String, expectedUrl: String = url) {
        val chooser = QrBrowserIntentFactory.createChooserIntent(url, CHOOSER_TITLE)
        assertNotNull("Valid web URL should create a system chooser", chooser)
        val actualChooser = chooser!!
        assertEquals(Intent.ACTION_CHOOSER, actualChooser.action)
        assertEquals(CHOOSER_TITLE, actualChooser.getCharSequenceExtra(Intent.EXTRA_TITLE)?.toString())
        val target = actualChooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        assertNotNull("Chooser should wrap an ACTION_VIEW intent", target)
        assertEquals(Intent.ACTION_VIEW, target?.action)
        org.junit.Assert.assertTrue(target?.hasCategory(Intent.CATEGORY_BROWSABLE) == true)
        assertEquals(expectedUrl, target?.dataString)
    }

    private companion object {
        const val CHOOSER_TITLE = "Choose a browser"
    }
}
