package com.dukangalex.angelacam

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.BaseColumns
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dukangalex.angelacam.databinding.ActivityGalleryBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Built-in gallery for AngelaCam captures and device media. Device media is queried only after
 * the user sees the disclosure and grants the matching runtime permission. External image
 * selection requests are routed to Android's Photo Picker and return scoped URI read grants.
 */
class GalleryActivity : AppCompatActivity() {

    private data class GalleryItem(
        val uri: Uri,
        val mime: String,
        val date: Long,
        val name: String,
        val ownedPosition: Int? = null
    ) {
        val isVideo: Boolean get() = mime.startsWith("video/")
    }

    private lateinit var binding: ActivityGalleryBinding
    private val ioExecutor: ExecutorService = Executors.newFixedThreadPool(3)
    private var disclosureShowing = false
    private val adapter = CaptureAdapter()

    private val mediaPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            updateMediaPermissionUi()
            loadItems()
        }

    private val singlePhotoPickerLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            returnExternalSelection(if (uri == null) emptyList() else listOf(uri))
        }

    private val multiplePhotoPickerLauncher =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
            returnExternalSelection(uris)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action == Intent.ACTION_GET_CONTENT || intent.action == Intent.ACTION_PICK) {
            if (!ExternalImageSelection.isImageRequest(intent)) {
                setResult(Activity.RESULT_CANCELED)
                finish()
            } else if (savedInstanceState == null) {
                launchExternalImagePicker(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
            }
            return
        }

        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        NavigationBarInsets.applyTo(binding.root)
        binding.tvTitle.setText(R.string.gallery_title)
        binding.btnDone.visibility = View.GONE
        binding.btnMediaAccess.setOnClickListener { showMediaPermissionDisclosure() }
        binding.btnBack.setOnClickListener { finish() }
        binding.recyclerView.layoutManager = GridLayoutManager(this, 3)
        binding.recyclerView.adapter = adapter
        adapter.onItemClick = { position ->
            adapter.itemAt(position)?.let { item ->
                if (item.ownedPosition != null) {
                    val detailIntent = Intent(this, DetailActivity::class.java).apply {
                        putExtra(DetailActivity.EXTRA_POSITION, item.ownedPosition)
                    }
                    startActivity(detailIntent)
                } else {
                    val viewIntent = Intent(this, ExternalViewActivity::class.java).apply {
                        action = Intent.ACTION_VIEW
                        setDataAndType(item.uri, item.mime)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(viewIntent)
                }
            }
        }
        updateMediaPermissionUi()
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return
        loadItems()
        val access = mediaAccess()
        if (!hasDesiredAccess(access) && !preferences().getBoolean(KEY_DISCLOSURE_SHOWN, false) && !disclosureShowing) {
            showMediaPermissionDisclosure()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ioExecutor.shutdown()
    }

    private fun preferences() = getPreferences(MODE_PRIVATE)

    private fun launchExternalImagePicker(allowMultiple: Boolean) {
        val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        if (allowMultiple) multiplePhotoPickerLauncher.launch(request)
        else singlePhotoPickerLauncher.launch(request)
    }

    private fun returnExternalSelection(uris: List<Uri>) {
        val result = ExternalImageSelection.createResult(uris, getString(R.string.app_name))
        if (result == null) setResult(Activity.RESULT_CANCELED)
        else setResult(Activity.RESULT_OK, result)
        finish()
    }

    private fun permissionNames(): Array<String> =
        GalleryMediaPermissionPolicy.permissionsFor(Build.VERSION.SDK_INT)

    private fun mediaAccess(): GalleryMediaAccess {
        val apiLevel = Build.VERSION.SDK_INT
        fun isGranted(permission: String) =
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        return GalleryMediaPermissionPolicy.fromGrantState(
            apiLevel = apiLevel,
            legacyReadGranted = apiLevel < Build.VERSION_CODES.TIRAMISU &&
                isGranted(Manifest.permission.READ_EXTERNAL_STORAGE),
            fullImagesGranted = apiLevel >= Build.VERSION_CODES.TIRAMISU &&
                isGranted(Manifest.permission.READ_MEDIA_IMAGES),
            fullVideosGranted = apiLevel >= Build.VERSION_CODES.TIRAMISU &&
                isGranted(Manifest.permission.READ_MEDIA_VIDEO),
            selectedOnlyGranted = apiLevel >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                isGranted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        )
    }

    private fun hasDesiredAccess(access: GalleryMediaAccess): Boolean =
        GalleryMediaPermissionPolicy.hasDesiredAccess(access)

    private fun hasFullDesiredAccess(access: GalleryMediaAccess): Boolean =
        GalleryMediaPermissionPolicy.hasFullAccess(access)

    private fun updateMediaPermissionUi() {
        if (!::binding.isInitialized || isFinishing || isDestroyed) return
        val access = mediaAccess()
        val fullAccess = hasFullDesiredAccess(access)
        binding.btnMediaAccess.visibility = if (fullAccess) View.GONE else View.VISIBLE
        binding.btnMediaAccess.setText(
            if (hasDesiredAccess(access)) R.string.gallery_permission_manage else R.string.gallery_permission_allow
        )
        binding.tvMediaAccessInfo.visibility = if (fullAccess) View.GONE else View.VISIBLE
        binding.tvMediaAccessInfo.setText(R.string.gallery_permission_summary)
    }

    private fun showMediaPermissionDisclosure() {
        if (disclosureShowing || isFinishing || isDestroyed) return
        disclosureShowing = true
        preferences().edit().putBoolean(KEY_DISCLOSURE_SHOWN, true).apply()
        AlertDialog.Builder(this)
            .setTitle(R.string.gallery_permission_title)
            .setMessage(R.string.gallery_permission_disclosure)
            .setPositiveButton(R.string.gallery_permission_continue) { _, _ -> requestDeviceMediaPermissions() }
            .setNegativeButton(R.string.gallery_permission_not_now, null)
            .setOnDismissListener { disclosureShowing = false }
            .show()
    }

    private fun requestDeviceMediaPermissions() {
        val requested = permissionNames()
        val access = mediaAccess()
        val previous = preferences().getStringSet(KEY_REQUESTED_PERMISSIONS, emptySet()).orEmpty()
        val permanentlyDenied = !access.selectedOnly && requested.any { permission ->
            permission in previous &&
                ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED &&
                !shouldShowRequestPermissionRationale(permission)
        }
        if (permanentlyDenied) {
            AlertDialog.Builder(this)
                .setTitle(R.string.gallery_permission_title)
                .setMessage(R.string.gallery_permission_settings_hint)
                .setPositiveButton(R.string.gallery_permission_settings) { _, _ ->
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }
                .setNegativeButton(R.string.gallery_permission_not_now, null)
                .show()
            return
        }
        preferences().edit().putStringSet(KEY_REQUESTED_PERMISSIONS, previous + requested).apply()
        mediaPermissionLauncher.launch(requested)
    }

    private fun loadItems() {
        ioExecutor.execute {
            // CaptureStore contains only media AngelaCam itself created and is safe without library permission.
            val ownItems = CaptureStore.list(this)
            val access = mediaAccess()
            val rows = linkedMapOf<String, GalleryItem>()
            ownItems.forEachIndexed { index, item ->
                val uri = Uri.parse(item.uri)
                rows[item.uri] = GalleryItem(uri, item.mime, item.date, item.name, index)
            }
            queryDeviceMedia(access).forEach { item -> rows.putIfAbsent(item.uri.toString(), item) }
            val allRows = rows.values.sortedByDescending { it.date }
            val visibleItems = allRows
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                adapter.submit(visibleItems)
                updateMediaPermissionUi()
                val emptyText = if (!hasDesiredAccess(access)) {
                    R.string.gallery_empty_no_access
                } else {
                    R.string.gallery_empty
                }
                binding.tvEmpty.setText(emptyText)
                binding.tvEmpty.visibility = if (visibleItems.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    /** Query only collections whose matching runtime permission is currently granted. */
    private fun queryDeviceMedia(access: GalleryMediaAccess): List<GalleryItem> {
        val result = mutableListOf<GalleryItem>()
        if (access.images) result += queryCollection(video = false)
        if (access.videos) result += queryCollection(video = true)
        return result
    }

    private fun queryCollection(video: Boolean): List<GalleryItem> {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val idColumn = if (video) MediaStore.Video.Media._ID else MediaStore.Images.Media._ID
        val fallbackMime = if (video) "video/*" else "image/*"
        val projection = arrayOf(
            idColumn,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED
        )
        return try {
            val rows = mutableListOf<GalleryItem>()
            contentResolver.query(
                collection,
                projection,
                null,
                null,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(idColumn)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val addedIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val modifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIndex)
                    val uri = ContentUris.withAppendedId(collection, id)
                    val name = cursor.getString(nameIndex).orEmpty().ifBlank { uri.lastPathSegment.orEmpty() }
                    val mime = cursor.getString(mimeIndex).orEmpty().ifBlank { fallbackMime }
                    val added = cursor.getLong(addedIndex)
                    val modified = cursor.getLong(modifiedIndex)
                    rows += GalleryItem(uri, mime, (if (added > 0) added else modified) * 1000L, name)
                }
            }
            rows
        } catch (e: SecurityException) {
            Log.w(TAG, "设备媒体权限在查询期间发生变化", e)
            emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "读取设备媒体失败", e)
            emptyList()
        }
    }

    private inner class CaptureAdapter : RecyclerView.Adapter<CaptureAdapter.VH>() {

        var onItemClick: ((Int) -> Unit)? = null
        private var items: List<GalleryItem> = emptyList()

        fun submit(newItems: List<GalleryItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        fun itemAt(position: Int): GalleryItem? = items.getOrNull(position)

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val ivThumb: ImageView = view.findViewById(R.id.ivThumb)
            val tvBadge: TextView = view.findViewById(R.id.tvVideoBadge)
            val tvSelection: TextView = view.findViewById(R.id.tvSelectionBadge)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_capture, parent, false)
            return VH(view)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.tvBadge.visibility = if (item.isVideo) View.VISIBLE else View.GONE
            holder.tvSelection.visibility = View.GONE
            holder.ivThumb.setImageDrawable(null)
            holder.ivThumb.tag = item.uri.toString()
            ioExecutor.execute {
                try {
                    val thumb: Bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        contentResolver.loadThumbnail(item.uri, Size(256, 256), null)
                    } else {
                        val id = ContentUris.parseId(item.uri)
                        if (item.isVideo) {
                            @Suppress("DEPRECATION")
                            MediaStore.Video.Thumbnails.getThumbnail(contentResolver, id, MediaStore.Video.Thumbnails.MINI_KIND, null)
                        } else {
                            @Suppress("DEPRECATION")
                            MediaStore.Images.Thumbnails.getThumbnail(contentResolver, id, MediaStore.Images.Thumbnails.MINI_KIND, null)
                        }
                    }
                    runOnUiThread {
                        if (!isFinishing && holder.ivThumb.tag == item.uri.toString()) holder.ivThumb.setImageBitmap(thumb)
                    }
                } catch (_: Exception) {
                    // Thumbnail failures leave the existing placeholder visible.
                }
            }
            holder.itemView.setOnClickListener {
                val adapterPosition = holder.bindingAdapterPosition
                if (adapterPosition != RecyclerView.NO_POSITION) onItemClick?.invoke(adapterPosition)
            }
        }
    }

    companion object {
        private const val TAG = "GalleryActivity"
        private const val KEY_DISCLOSURE_SHOWN = "gallery_media_disclosure_shown"
        private const val KEY_REQUESTED_PERMISSIONS = "gallery_media_permissions_requested"
    }
}
