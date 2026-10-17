package com.dukangalex.angelacam

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.dukangalex.angelacam.databinding.ActivityAboutBinding
import java.util.concurrent.Executors

/** 关于页：版本、简介、隐私说明、手动更新指引、开源许可 */
class AboutActivity : AppCompatActivity() {

    companion object {
        const val RELEASES_URL = "https://github.com/dukangalex/AngelaCam/releases"
    }

    private lateinit var binding: ActivityAboutBinding
    private val ioExecutor = Executors.newSingleThreadExecutor()

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

        binding.btnCheckUpdate.setOnClickListener { checkUpdate() }

        binding.btnOpenReleases.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL)))
            } catch (e: Exception) {
                // 没有浏览器可用时静默忽略
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ioExecutor.shutdown()
    }

    /** 用户主动点击时才联网检查 */
    private fun checkUpdate() {
        binding.btnCheckUpdate.isEnabled = false
        binding.tvUpdateStatus.visibility = View.VISIBLE
        binding.tvUpdateStatus.text = getString(R.string.about_checking)
        ioExecutor.execute {
            when (val result = UpdateChecker.check(this)) {
                is UpdateChecker.Result.Available -> runOnUiThread {
                    binding.btnCheckUpdate.isEnabled = true
                    binding.tvUpdateStatus.text =
                        getString(R.string.about_update_available, result.info.version)
                    UpdateInstaller.promptUpdate(this, result.info)
                }
                is UpdateChecker.Result.UpToDate -> runOnUiThread {
                    binding.btnCheckUpdate.isEnabled = true
                    binding.tvUpdateStatus.text = getString(R.string.about_up_to_date)
                }
                is UpdateChecker.Result.Failed -> runOnUiThread {
                    binding.btnCheckUpdate.isEnabled = true
                    binding.tvUpdateStatus.text = getString(R.string.about_check_failed)
                }
            }
        }
    }
}
