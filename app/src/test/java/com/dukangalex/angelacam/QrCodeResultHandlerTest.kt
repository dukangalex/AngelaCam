package com.dukangalex.angelacam

import com.google.mlkit.vision.barcode.common.Barcode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrCodeResultHandlerTest {
    @Test
    fun mapsMlKitQrFormatToQrCandidate() {
        assertEquals(
            QrCodeCandidate(QrCodeFormat.QR_CODE, "payload"),
            QrCodeResultHandler.candidateFromDecodedFormat(Barcode.FORMAT_QR_CODE, "payload")
        )
    }

    @Test
    fun mapsOtherMlKitFormatsToNonQrCandidate() {
        assertEquals(
            QrCodeCandidate(QrCodeFormat.OTHER, "payload"),
            QrCodeResultHandler.candidateFromDecodedFormat(Barcode.FORMAT_CODE_128, "payload")
        )
    }

    @Test
    fun returnsFirstNonEmptyQrPayloadUnchanged() {
        val result = QrCodeResultHandler.firstResult(
            listOf(
                QrCodeCandidate(QrCodeFormat.QR_CODE, ""),
                QrCodeCandidate(QrCodeFormat.QR_CODE, "  plain text\n"),
                QrCodeCandidate(QrCodeFormat.QR_CODE, "later payload")
            )
        )

        assertEquals(QrScanResult("  plain text\n"), result)
    }

    @Test
    fun ignoresNonQrAndEmptyDetections() {
        val result = QrCodeResultHandler.firstResult(
            listOf(
                QrCodeCandidate(QrCodeFormat.OTHER, "https://not-a-qr-result.example"),
                QrCodeCandidate(QrCodeFormat.QR_CODE, null),
                QrCodeCandidate(QrCodeFormat.QR_CODE, "")
            )
        )

        assertNull(result)
    }

    @Test
    fun returnsNoResultWhenThereAreNoDetections() {
        assertNull(QrCodeResultHandler.firstResult(emptyList()))
    }
}
