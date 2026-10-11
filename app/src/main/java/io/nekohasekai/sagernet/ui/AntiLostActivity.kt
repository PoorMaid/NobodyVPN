package io.nekohasekai.sagernet.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.databinding.ActivityNobodyAntilostBinding
import io.nekohasekai.sagernet.nobody.NobodyLog
import io.nekohasekai.sagernet.utils.Theme
class AntiLostActivity : AppCompatActivity() {
    private lateinit var binding: ActivityNobodyAntilostBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            NobodyLog.checkpoint("AntiLostActivity.onCreate start")
            Theme.apply(this)
            NobodyLog.checkpoint("AntiLostActivity.afterThemeApply")
        } catch (e: Throwable) {
            NobodyLog.checkpoint("AntiLostActivity.themeApply ERROR: ${e.javaClass.simpleName}: ${e.message}")
        }
        super.onCreate(savedInstanceState)
        NobodyLog.checkpoint("AntiLostActivity.afterSuperCreate")
        binding = ActivityNobodyAntilostBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.title = getString(R.string.nobody_antilost_title)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnDirectApk.setOnClickListener { openUrl("https://nobady.bbroot.com/morevpn.html") }
        binding.btnPrivado.setOnClickListener { openUrl("https://privadovpn.com") }
        binding.btnProton.setOnClickListener { openUrl("https://protonvpn.com") }
        binding.btnWarp.setOnClickListener { openUrl("https://one.one.one.one") }
        binding.btnNekoboxDl.setOnClickListener { openUrl("https://github.com/MatsuriDayo/NekoBoxForAndroid/releases") }
        binding.btnV2rayng.setOnClickListener { openUrl("https://github.com/2dust/v2rayNG/releases") }
        binding.btnClashMeta.setOnClickListener { openUrl("https://github.com/MetaCubeX/ClashMetaForAndroid/releases") }
        binding.btnSingbox.setOnClickListener { openUrl("https://github.com/SagerNet/sing-box/releases") }
        binding.linkEndgfw.setOnClickListener { openUrl("https://end-gfw.com") }
        binding.btnFmt1.setOnClickListener {
            copyToClipboard("https://raw.githubusercontent.com/hello-world-1989/cn-news/main/end-gfw-free")
        }
        binding.btnFmt2.setOnClickListener {
            copyToClipboard("https://raw.githubusercontent.com/hello-world-1989/cn-news/main/clash-free.yaml")
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show()
        }
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("subscription", text))
        Toast.makeText(this, "已复制订阅链接", Toast.LENGTH_SHORT).show()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
