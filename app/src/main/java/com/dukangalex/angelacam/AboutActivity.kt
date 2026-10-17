package com.dukangalex.angelacam

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.dukangalex.angelacam.databinding.ActivityAboutBinding

/** 关于页：版本、简介、隐私说明、手动更新指引、开源许可 */
class AboutActivity : AppCompatActivity() {

    companion object {
        const val RELEASES_URL = "https://github.com/dukangalex/AngelaCam/releases"
    }

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            null
        }
        binding.tvVersion.text = getString(R.string.about_version, versionName ?: "—")

        binding.btnOpenReleases.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL)))
            } catch (e: Exception) {
                // 没有浏览器可用时静默忽略
            }
        }
    }
}
