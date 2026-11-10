package com.dukangalex.angelacam

import com.google.mlkit.vision.barcode.common.Barcode

/** The decoder's format is mapped here so result handling stays easy to unit-test. */
internal enum class QrCodeFormat {
    QR_CODE,
    OTHER
}

internal data class QrCodeCandidate(
    val format: QrCodeFormat,
    val rawValue: String?
)

internal data class QrScanResult(val text: String)

internal object QrCodeResultHandler {
    fun candidateFromDecodedFormat(format: Int, rawValue: String?): QrCodeCandidate =
        QrCodeCandidate(
            format = if (format == Barcode.FORMAT_QR_CODE) QrCodeFormat.QR_CODE else QrCodeFormat.OTHER,
            rawValue = rawValue
        )

    /** Return the first non-empty QR payload unchanged; never interpret it as an action. */
    fun firstResult(candidates: Iterable<QrCodeCandidate>): QrScanResult? {
        val text = candidates.firstOrNull {
            it.format == QrCodeFormat.QR_CODE && !it.rawValue.isNullOrEmpty()
        }?.rawValue ?: return null
        return QrScanResult(text)
    }
}
