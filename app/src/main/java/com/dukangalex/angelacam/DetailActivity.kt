package com.dukangalex.angelacam

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.MediaController
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.dukangalex.angelacam.databinding.ActivityDetailBinding

class DetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URI = "extra_uri"
        const val EXTRA_MIME = "extra_mime"
    }

    private lateinit var binding: ActivityDetailBinding
    private var uri: Uri? = null
    private var mime: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val uriStr = intent.getStringExtra(EXTRA_URI)
        mime = intent.getStringExtra(EXTRA_MIME) ?: ""
        if (uriStr.isNullOrEmpty()) {
            finish()
            return
        }
        uri = Uri.parse(uriStr)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnDelete.setOnClickListener { confirmDelete() }
        binding.btnShare.setOnClickListener { share() }
        binding.btnEdit.setOnClickListener { openEditor() }

        if (mime.startsWith("video")) {
            binding.videoView.visibility = View.VISIBLE
            binding.btnEdit.visibility = View.GONE
            binding.videoView.setVideoURI(uri)
            val controller = MediaController(this)
            controller.setAnchorView(binding.videoView)
            binding.videoView.setMediaController(controller)
            binding.videoView.setOnPreparedListener { it.start() }
        } else {
            binding.ivPhoto.visibility = View.VISIBLE
            binding.ivPhoto.setImageURI(uri)
        }
    }

    override fun onPause() {
        super.onPause()
        if (binding.videoView.isPlaying) binding.videoView.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.videoView.stopPlayback()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_confirm_title)
            .setPositiveButton(R.string.delete_confirm_ok) { _, _ -> doDelete() }
            .setNegativeButton(R.string.delete_confirm_cancel, null)
            .show()
    }

    private fun doDelete() {
        val u = uri ?: return
        try {
            contentResolver.delete(u, null, null)
            CaptureStore.remove(this, u.toString())
            Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "删除失败", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    private fun openEditor() {
        val u = uri ?: return
        if (mime.startsWith("video")) {
            Toast.makeText(this, "视频暂不支持编辑", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, EditActivity::class.java).apply {
            putExtra(EditActivity.EXTRA_URI, u.toString())
        }
        startActivity(intent)
    }

    private fun share() {
        val u = uri ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime.ifEmpty { "*/*" }
            putExtra(Intent.EXTRA_STREAM, u)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, null))
        } catch (e: Exception) {
            Toast.makeText(this, "没有可用的分享应用", Toast.LENGTH_SHORT).show()
        }
    }
}
