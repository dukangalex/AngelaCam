package com.dukangalex.angelacam

import android.Manifest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryMediaPermissionPolicyTest {
    @Test
    fun requestsVersionAppropriatePermissionsForBuiltInGallery() {
        assertArrayEquals(
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
            GalleryMediaPermissionPolicy.permissionsFor(32)
        )
        assertArrayEquals(
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO),
            GalleryMediaPermissionPolicy.permissionsFor(33)
        )
        assertArrayEquals(
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            ),
            GalleryMediaPermissionPolicy.permissionsFor(34)
        )
    }

    @Test
    fun selectedOnlyGrantIsPartialAccessNotFullLibraryAccess() {
        val access = GalleryMediaPermissionPolicy.fromGrantState(
            apiLevel = 35,
            legacyReadGranted = false,
            fullImagesGranted = false,
            fullVideosGranted = false,
            selectedOnlyGranted = true
        )

        assertTrue(access.images)
        assertTrue(access.videos)
        assertTrue(access.selectedOnly)
        assertTrue(GalleryMediaPermissionPolicy.hasDesiredAccess(access))
        assertFalse(access.fullImages)
        assertFalse(access.fullVideos)
        assertFalse(GalleryMediaPermissionPolicy.hasFullAccess(access))
    }

    @Test
    fun legacyReadPermissionCoversBothCollectionsOnOlderAndroid() {
        val access = GalleryMediaPermissionPolicy.fromGrantState(
            apiLevel = 32,
            legacyReadGranted = true,
            fullImagesGranted = false,
            fullVideosGranted = false,
            selectedOnlyGranted = false
        )

        assertTrue(access.images)
        assertTrue(access.videos)
        assertTrue(GalleryMediaPermissionPolicy.hasFullAccess(access))
    }
}
