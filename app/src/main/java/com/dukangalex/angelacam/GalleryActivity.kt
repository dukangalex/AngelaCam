package com.dukangalex.angelacam

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dukangalex.angelacam.databinding.ActivityGalleryBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 自带相册：只展示本应用拍摄的照片/视频。
 * 不申请任何存储读取权限，不扫描用户相册里的其他内容。
 */
class GalleryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGalleryBinding
    private val ioExecutor: ExecutorService = Executors.newFixedThreadPool(3)
    private val adapter = CaptureAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.recyclerView.layoutManager = GridLayoutManager(this, 3)
        binding.recyclerView.adapter = adapter
        adapter.onItemClick = { item ->
            val intent = Intent(this, DetailActivity::class.java).apply {
                putExtra(DetailActivity.EXTRA_URI, item.uri)
                putExtra(DetailActivity.EXTRA_MIME, item.mime)
            }
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        loadItems()
    }

    override fun onDestroy() {
        super.onDestroy()
        ioExecutor.shutdown()
    }

    private fun loadItems() {
        ioExecutor.execute {
            val items = CaptureStore.list(this)
            runOnUiThread {
                adapter.submit(items)
                binding.tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private inner class CaptureAdapter : RecyclerView.Adapter<CaptureAdapter.VH>() {

        var onItemClick: ((CaptureStore.Item) -> Unit)? = null
        private var items: List<CaptureStore.Item> = emptyList()

        fun submit(newItems: List<CaptureStore.Item>) {
            items = newItems
            notifyDataSetChanged()
        }

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val ivThumb: ImageView = view.findViewById(R.id.ivThumb)
            val tvBadge: TextView = view.findViewById(R.id.tvVideoBadge)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_capture, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.tvBadge.visibility = if (item.isVideo) View.VISIBLE else View.GONE
            holder.ivThumb.setImageDrawable(null)
            holder.ivThumb.tag = item.uri
            ioExecutor.execute {
                try {
                    val uri = Uri.parse(item.uri)
                    val thumb: Bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        contentResolver.loadThumbnail(uri, Size(256, 256), null)
                    } else {
                        @Suppress("DEPRECATION")
                        android.provider.MediaStore.Images.Thumbnails.getThumbnail(
                            contentResolver,
                            android.content.ContentUris.parseId(uri),
                            android.provider.MediaStore.Images.Thumbnails.MINI_KIND,
                            null
                        )
                    }
                    runOnUiThread {
                        if (holder.ivThumb.tag == item.uri) {
                            holder.ivThumb.setImageBitmap(thumb)
                        }
                    }
                } catch (e: Exception) {
                    // 缩略图加载失败，保持占位
                }
            }
            holder.itemView.setOnClickListener { onItemClick?.invoke(item) }
        }
    }
}
