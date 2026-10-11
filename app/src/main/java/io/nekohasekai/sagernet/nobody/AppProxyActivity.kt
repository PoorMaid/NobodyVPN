package io.nekohasekai.sagernet.nobody

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.switchmaterial.SwitchMaterial
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.ActivityNobodyAppProxyBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppProxyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNobodyAppProxyBinding
    private val adapter = AppAdapter()
    private var allApps: List<AppEntry> = emptyList()
    private val selected: MutableSet<String> = mutableSetOf()
    private var query: String = ""
    private var bindingUi = false

    data class AppEntry(val pkg: String, val label: String, val icon: android.graphics.drawable.Drawable)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNobodyAppProxyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.switchEnable.isChecked = DataStore.proxyApps
        binding.switchEnable.setOnCheckedChangeListener { _, _ ->
            updateEnabledState()
        }

        binding.modeGroup.check(if (DataStore.bypass) R.id.mode_bypass else R.id.mode_only)
        binding.modeGroup.addOnButtonCheckedListener { _, _, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            updateModeLabel()
        }
        updateModeLabel()

        binding.search.doOnTextChanged { text, _, _, _ ->
            query = text?.toString()?.trim()?.lowercase() ?: ""
            applyFilter()
        }

        binding.btnApply.setOnClickListener { applyNow() }
        binding.btnRefresh.setOnClickListener { loadApps() }

        loadApps()
        updateEnabledState()
    }

    private fun hasAppListPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 30) return true
        return try {
            packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                0
            ).isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    private fun requestAppListPermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + packageName)))
            } catch (e: Exception) {
            }
        }
        Toast.makeText(this, getString(R.string.nobody_perm_hint), Toast.LENGTH_LONG).show()
    }

    private fun applyNow() {
        DataStore.proxyApps = binding.switchEnable.isChecked
        DataStore.bypass = binding.modeGroup.checkedButtonId == R.id.mode_bypass
        DataStore.individual = TextUtils.join("\n", selected.toList())
        Toast.makeText(this, getString(R.string.nobody_apply_done), Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            NobodySubscriptions.reloadForAppProxyChanged()
            finish()
        }
    }

    private fun updateModeLabel() {
        val id = binding.modeGroup.checkedButtonId
        val text = when (id) {
            R.id.mode_bypass -> getString(R.string.nobody_mode_bypass)
            R.id.mode_only -> getString(R.string.nobody_mode_only)
            else -> ""
        }
        binding.modeLabel.text = getString(R.string.nobody_current_mode_prefix, text)
    }

    private fun updateEnabledState() {
        val on = binding.switchEnable.isChecked
        binding.modeGroup.isEnabled = on
        binding.modeGroup.findViewById<View>(R.id.mode_bypass)?.isEnabled = on
        binding.modeGroup.findViewById<View>(R.id.mode_only)?.isEnabled = on
        binding.recycler.alpha = if (on) 1f else 0.4f
    }

    private fun loadApps() {
        lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) {
                val pm = packageManager
                val apps = try {
                    pm.getInstalledApplications(PackageManager.GET_META_DATA)
                } catch (e: Exception) {
                    emptyList()
                }
                val self = packageName
                apps.asSequence()
                    .filter { it.packageName != self }
                    .map { info -> AppEntry(info.packageName, info.loadLabel(pm).toString(), info.loadIcon(pm)) }
                    .toList()
            }
            allApps = entries
            val raw = DataStore.individual
            selected.clear()
            selected.addAll(raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() })
            applyFilter()
            if (entries.isEmpty()) {
                Toast.makeText(this@AppProxyActivity, getString(R.string.nobody_refresh_empty), Toast.LENGTH_LONG).show()
                requestAppListPermission()
            }
        }
    }

    private fun applyFilter() {
        val base = if (query.isEmpty()) allApps
        else allApps.filter { it.label.lowercase().contains(query) || it.pkg.lowercase().contains(query) }
        val sorted = base.sortedWith(compareBy({ !selected.contains(it.pkg) }, { it.label.lowercase() }))
        adapter.submit(sorted)
    }

    fun toggle(pkg: String, checked: Boolean) {
        if (checked) selected.add(pkg) else selected.remove(pkg)
    }

    inner class AppAdapter : RecyclerView.Adapter<AppAdapter.Holder>() {
        private var items: List<AppEntry> = emptyList()

        fun submit(list: List<AppEntry>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_nobody_app, parent, false)
            return Holder(v)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.icon.setImageDrawable(item.icon)
            holder.label.text = item.label
            holder.pkg.text = item.pkg

            holder.check.setOnCheckedChangeListener(null)
            holder.check.isChecked = selected.contains(item.pkg)
            holder.check.setOnCheckedChangeListener { _, checked ->
                if (bindingUi) return@setOnCheckedChangeListener
                toggle(item.pkg, checked)
                applyFilter()
            }

            holder.itemView.setOnClickListener {
                val now = !selected.contains(item.pkg)
                toggle(item.pkg, now)
                bindingUi = true
                holder.check.isChecked = now
                bindingUi = false
                applyFilter()
            }
        }

        override fun getItemCount() = items.size

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.app_icon)
            val label: TextView = v.findViewById(R.id.app_label)
            val pkg: TextView = v.findViewById(R.id.app_pkg)
            val check: SwitchMaterial = v.findViewById(R.id.app_check)
        }
    }
}
