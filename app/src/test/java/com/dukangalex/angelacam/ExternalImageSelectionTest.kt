package com.dukangalex.angelacam

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExternalImageSelectionTest {
    @Test
    fun acceptsImageGetContentAndImageMediaStorePickRequests() {
        val getContent = Intent(Intent.ACTION_GET_CONTENT).setType("IMAGE/JPEG")
        val pick = Intent(Intent.ACTION_PICK).setData(Uri.parse("content://media/external/images/media"))

        assertTrue(ExternalImageSelection.isImageRequest(getContent))
        assertTrue(ExternalImageSelection.isImageRequest(pick))
    }

    @Test
    fun acceptsImageMimeFromExtraAndRejectsNonImageRequests() {
        val extraMime = Intent(Intent.ACTION_GET_CONTENT)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "image/png"))
        val nonImage = Intent(Intent.ACTION_GET_CONTENT).setType("video/*")
        val wrongAction = Intent(Intent.ACTION_SEND).setType("image/*")

        assertTrue(ExternalImageSelection.isImageRequest(extraMime))
        assertFalse(ExternalImageSelection.isImageRequest(nonImage))
        assertFalse(ExternalImageSelection.isImageRequest(wrongAction))
    }

    @Test
    fun resultContainsOnlyDistinctSelectedUrisAndReadGrant() {
        val first = Uri.parse("content://media/picker/1")
        val second = Uri.parse("content://media/picker/2")
        val result = ExternalImageSelection.createResult(listOf(first, second, first), "AngelaCam")

        assertEquals(first, result?.data)
        assertEquals("image/*", result?.type)
        assertNotEquals(0, result!!.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertEquals(0, result.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertEquals(listOf(first, second), (0 until result.clipData!!.itemCount).map { result.clipData!!.getItemAt(it).uri })
        assertEquals(listOf(first, second), result.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM))
    }

    @Test
    fun rejectsEmptyAndNonContentUriResults() {
        assertNull(ExternalImageSelection.createResult(emptyList(), "AngelaCam"))
        assertNull(
            ExternalImageSelection.createResult(
                listOf(Uri.parse("file:///sdcard/photo.jpg")),
                "AngelaCam"
            )
        )
    }
}
