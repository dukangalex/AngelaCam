package com.dukangalex.angelacam

import android.content.ClipData
import android.content.Intent
import android.net.Uri

/** Intent and result handling for images requested by other apps. */
internal object ExternalImageSelection {
    fun isImageRequest(request: Intent): Boolean {
        if (request.action != Intent.ACTION_GET_CONTENT && request.action != Intent.ACTION_PICK) return false
        val types = mutableListOf<String>()
        request.type?.let { types.add(it) }
        runCatching { request.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.forEach(types::add) }
        val mimeMatches = types.any {
            it.substringBefore(';').trim().startsWith("image/", ignoreCase = true)
        }
        val imageCollection = request.action == Intent.ACTION_PICK && request.data?.let { uri ->
            val segments = uri.pathSegments
            val imagesIndex = segments.indexOfFirst { it.equals("images", ignoreCase = true) }
            uri.scheme.equals("content", ignoreCase = true) &&
                uri.authority.equals("media", ignoreCase = true) &&
                imagesIndex >= 0 && segments.getOrNull(imagesIndex + 1).equals("media", ignoreCase = true)
        } == true
        return mimeMatches || imageCollection
    }

    /** Build the standard result shape and grant the caller read access only to selected URIs. */
    fun createResult(uris: List<Uri>, label: String): Intent? {
        val selected = uris
            .filter { it.scheme.equals("content", ignoreCase = true) }
            .distinct()
        val first = selected.firstOrNull() ?: return null
        val clip = ClipData.newRawUri(label, first)
        selected.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        return Intent().apply {
            setDataAndType(first, "image/*")
            clipData = clip
            if (selected.size > 1) {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(selected))
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
