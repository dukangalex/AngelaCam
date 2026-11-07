package com.dukangalex.angelacam

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.ClipData
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
 * Built-in gallery and ACTION_GET_CONTENT/ACTION_PICK image picker.
 * Device media is queried only after the user sees the disclosure and grants the matching runtime permission.
 * AngelaCam's own CaptureStore items remain available without device-library permission.
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

    private data class MediaAccess(
        val images: Boolean,
        val videos: Boolean,
        val fullImages: Boolean,
        val fullVideos: Boolean,
        val selectedOnly: Boolean
    )

    private lateinit var binding: ActivityGalleryBinding
    private val ioExecutor: ExecutorService = Executors.newFixedThreadPool(3)
    private var selectionMode = false
    private var allowMultiple = false
    private var disclosureShowing = false
    private val selectedItems = linkedMapOf<String, GalleryItem>()
    private val adapter = CaptureAdapter()

    private val mediaPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            updateMediaPermissionUi()
            loadItems()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        NavigationBarInsets.applyTo(binding.root)

        selectionMode = intent.action == Intent.ACTION_GET_CONTENT || intent.action == Intent.ACTION_PICK
        if (selectionMode && !isImageRequest(intent)) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        allowMultiple = selectionMode && intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
        binding.tvTitle.setText(if (selectionMode) R.string.gallery_select_title else R.string.gallery_title)
        binding.btnDone.visibility = if (allowMultiple) View.VISIBLE else View.GONE
        binding.btnDone.setOnClickListener { returnSelection(selectedItems.values.toList()) }
        binding.btnMediaAccess.setOnClickListener { showMediaPermissionDisclosure() }
        binding.btnBack.setOnClickListener { finish() }
        binding.recyclerView.layoutManager = GridLayoutManager(this, 3)
        binding.recyclerView.adapter = adapter
        adapter.onItemClick = { position ->
            adapter.itemAt(position)?.let { item ->
                if (selectionMode) {
                    if (allowMultiple) {
                        if (selectedItems.remove(item.uri.toString()) == null) {
                            selectedItems[item.uri.toString()] = item
                        }
                        adapter.notifyDataSetChanged()
                        updateDoneButton()
                    } else {
                        returnSelection(listOf(item))
                    }
                } else if (item.ownedPosition != null) {
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
        updateDoneButton()
        updateMediaPermissionUi()
    }

    override fun onResume() {
        super.onResume()
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

    private fun isImageRequest(request: Intent): Boolean {
        if (request.action != Intent.ACTION_GET_CONTENT && request.action != Intent.ACTION_PICK) return false
        val types = mutableListOf<String>()
        request.type?.let(types::add)
        runCatching { request.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.forEach(types::add) }
        val mimeMatches = types.any { it.substringBefore(';').trim().startsWith("image/", ignoreCase = true) }
        val dataUri = request.data
        val imageCollection = request.action == Intent.ACTION_PICK && dataUri?.let { uri ->
            val segments = uri.pathSegments
            val imagesIndex = segments.indexOf("images")
            uri.scheme == "content" && uri.authority == "media" &&
                imagesIndex >= 0 && segments.getOrNull(imagesIndex + 1) == "media"
        } == true
        return mimeMatches || imageCollection
    }

    private fun permissionNames(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> buildList {
            add(Manifest.permission.READ_MEDIA_IMAGES)
            if (!selectionMode) add(Manifest.permission.READ_MEDIA_VIDEO)
            add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        }.toTypedArray()
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
            if (selectionMode) arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            else arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        }
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun mediaAccess(): MediaAccess {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
            return MediaAccess(granted, granted, granted, granted, false)
        }
        val fullImages = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) ==
            PackageManager.PERMISSION_GRANTED
        val fullVideos = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) ==
            PackageManager.PERMISSION_GRANTED
        val selectedOnly = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) ==
            PackageManager.PERMISSION_GRANTED
        return MediaAccess(fullImages || selectedOnly, fullVideos || selectedOnly, fullImages, fullVideos, selectedOnly)
    }

    private fun hasDesiredAccess(access: MediaAccess): Boolean =
        if (selectionMode) access.images else access.images || access.videos

    private fun hasFullDesiredAccess(access: MediaAccess): Boolean =
        if (selectionMode) access.fullImages else access.fullImages && access.fullVideos

    private fun updateMediaPermissionUi() {
        if (!::binding.isInitialized || isFinishing || isDestroyed) return
        val access = mediaAccess()
        val fullAccess = hasFullDesiredAccess(access)
        binding.btnMediaAccess.visibility = if (fullAccess) View.GONE else View.VISIBLE
        binding.btnMediaAccess.setText(
            if (hasDesiredAccess(access)) R.string.gallery_permission_manage else R.string.gallery_permission_allow
        )
        binding.tvMediaAccessInfo.visibility = if (fullAccess) View.GONE else View.VISIBLE
        binding.tvMediaAccessInfo.setText(
            if (selectionMode) R.string.gallery_permission_images_summary else R.string.gallery_permission_summary
        )
        updateDoneButton()
    }

    private fun showMediaPermissionDisclosure() {
        if (disclosureShowing || isFinishing || isDestroyed) return
        disclosureShowing = true
        preferences().edit().putBoolean(KEY_DISCLOSURE_SHOWN, true).apply()
        val message = if (selectionMode) {
            R.string.gallery_permission_images_disclosure
        } else {
            R.string.gallery_permission_disclosure
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.gallery_permission_title)
            .setMessage(message)
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

    private fun updateDoneButton() {
        if (!::binding.isInitialized || !allowMultiple) return
        binding.btnDone.text = getString(R.string.gallery_select_done_count, selectedItems.size)
        binding.btnDone.isEnabled = selectedItems.isNotEmpty()
        binding.btnDone.alpha = if (selectedItems.isNotEmpty()) 1f else 0.5f
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
            val visibleItems = if (selectionMode) allRows.filter { it.mime.startsWith("image/") && it.uri.scheme == "content" }
            else allRows
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val available = visibleItems.mapTo(hashSetOf()) { it.uri.toString() }
                selectedItems.keys.retainAll(available)
                adapter.submit(visibleItems)
                updateMediaPermissionUi()
                val emptyText = when {
                    visibleItems.isNotEmpty() -> R.string.gallery_empty
                    !hasDesiredAccess(access) && selectionMode -> R.string.gallery_select_empty_no_access
                    !hasDesiredAccess(access) -> R.string.gallery_empty_no_access
                    selectionMode -> R.string.gallery_select_empty
                    else -> R.string.gallery_empty
                }
                binding.tvEmpty.setText(emptyText)
                binding.tvEmpty.visibility = if (visibleItems.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    /** Query only collections whose matching runtime permission is currently granted. */
    private fun queryDeviceMedia(access: MediaAccess): List<GalleryItem> {
        val result = mutableListOf<GalleryItem>()
        if (access.images) result += queryCollection(video = false)
        if (!selectionMode && access.videos) result += queryCollection(video = true)
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

    private fun returnSelection(items: List<GalleryItem>) {
        val selected = items.filter { it.mime.startsWith("image/") && it.uri.scheme == "content" }
        val first = selected.firstOrNull()
        if (first == null) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        val result = Intent().apply {
            setDataAndType(first.uri, first.mime)
            clipData = ClipData.newUri(contentResolver, getString(R.string.app_name), first.uri)
            selected.drop(1).forEach { item -> clipData?.addItem(ClipData.Item(item.uri)) }
            if (selected.size > 1) {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(selected.map { it.uri }))
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        setResult(Activity.RESULT_OK, result)
        finish()
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
            holder.tvSelection.visibility =
                if (selectionMode && selectedItems.containsKey(item.uri.toString())) View.VISIBLE else View.GONE
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
