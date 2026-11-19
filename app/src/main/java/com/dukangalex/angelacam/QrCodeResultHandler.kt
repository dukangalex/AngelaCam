package com.dukangalex.angelacam

import com.google.mlkit.vision.barcode.common.Barcode
import java.net.URI
import java.util.Locale

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

    /** Return a canonical HTTP(S) URL suitable for Android intent-filter matching. */
    fun browserUrlOrNull(rawText: String): String? {
        val candidate = rawText.trim()
        if (candidate.isEmpty()) return null
        val uri = try {
            URI(candidate)
        } catch (_: Exception) {
            return null
        }
        val scheme = uri.scheme ?: return null
        if (!scheme.equals("http", ignoreCase = true) &&
            !scheme.equals("https", ignoreCase = true)
        ) return null
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        if (uri.isOpaque || uri.port !in -1..65535) return null

        val normalizedHost = when {
            host.startsWith("[") && host.endsWith("]") -> host.lowercase(Locale.ROOT)
            ':' in host -> "[${host.lowercase(Locale.ROOT)}]"
            else -> host.lowercase(Locale.ROOT)
        }
        val normalizedAuthority = buildString {
            uri.rawUserInfo?.let { append(it).append('@') }
            append(normalizedHost)
            if (uri.port >= 0) append(':').append(uri.port)
        }
        return buildString {
            append(scheme.lowercase(Locale.ROOT))
            append("://")
            append(normalizedAuthority)
            append(uri.rawPath.orEmpty())
            uri.rawQuery?.let { append('?').append(it) }
            uri.rawFragment?.let { append('#').append(it) }
        }
    }
}
