package com.dukangalex.angelacam

import android.Manifest

internal data class GalleryMediaAccess(
    val images: Boolean,
    val videos: Boolean,
    val fullImages: Boolean,
    val fullVideos: Boolean,
    val selectedOnly: Boolean
)

/** Permission policy for the built-in gallery only; the system Photo Picker uses URI grants. */
internal object GalleryMediaPermissionPolicy {
    private const val API_ANDROID_13 = 33
    private const val API_ANDROID_14 = 34

    fun permissionsFor(apiLevel: Int): Array<String> = when {
        apiLevel >= API_ANDROID_14 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )
        apiLevel >= API_ANDROID_13 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun fromGrantState(
        apiLevel: Int,
        legacyReadGranted: Boolean,
        fullImagesGranted: Boolean,
        fullVideosGranted: Boolean,
        selectedOnlyGranted: Boolean
    ): GalleryMediaAccess {
        if (apiLevel < API_ANDROID_13) {
            return GalleryMediaAccess(
                images = legacyReadGranted,
                videos = legacyReadGranted,
                fullImages = legacyReadGranted,
                fullVideos = legacyReadGranted,
                selectedOnly = false
            )
        }
        val selectedOnly = apiLevel >= API_ANDROID_14 && selectedOnlyGranted
        return GalleryMediaAccess(
            images = fullImagesGranted || selectedOnly,
            videos = fullVideosGranted || selectedOnly,
            fullImages = fullImagesGranted,
            fullVideos = fullVideosGranted,
            selectedOnly = selectedOnly
        )
    }

    fun hasDesiredAccess(access: GalleryMediaAccess): Boolean = access.images || access.videos

    fun hasFullAccess(access: GalleryMediaAccess): Boolean = access.fullImages && access.fullVideos
}
