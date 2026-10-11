package io.nekohasekai.sagernet.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.FragmentNobodyConnectBinding
import io.nekohasekai.sagernet.nobody.AppProxyActivity
import io.nekohasekai.sagernet.nobody.NobodySubscriptions
import io.nekohasekai.sagernet.nobody.ServerListActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ConnectFragment : Fragment() {

    private var _binding: FragmentNobodyConnectBinding? = null
    private val binding get() = _binding!!

    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                SagerNet.startService()
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentNobodyConnectBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        NobodySubscriptions.onPhaseChanged = { phase ->
            view.post { renderPhase(phase) }
        }
        NobodySubscriptions.onStatusChanged = { text ->
            view.post { binding.serverCount.text = text }
        }
        NobodySubscriptions.onListChanged = {
            view.post { refreshServerInfo() }
        }

        binding.btnConnect.setOnClickListener {
            onBigButton()
        }
        binding.batteryWarn.setOnClickListener {
            requestBatteryExemption()
        }
        binding.rowServerList.setOnClickListener {
            startActivity(Intent(requireContext(), ServerListActivity::class.java))
        }
        binding.btnAppProxy.setOnClickListener {
            startActivity(Intent(requireContext(), AppProxyActivity::class.java))
        }

        NobodySubscriptions.syncPhaseFromService()
        renderPhase(NobodySubscriptions.phase)
        refreshServerInfo()
        updateBatteryWarn()
    }

    override fun onResume() {
        super.onResume()
        _binding?.let { updateBatteryWarn() }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = requireContext().getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(requireContext().packageName)
    }

    private fun updateBatteryWarn() {
        binding.batteryWarn.visibility =
            if (isIgnoringBatteryOptimizations()) View.GONE else View.VISIBLE
    }

    private fun requestBatteryExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:" + requireContext().packageName)
            startActivity(intent)
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                binding.statusText.text = getString(R.string.nobody_battery_warn)
            }
        }
    }

    private fun onBigButton() {
        val running = NobodySubscriptions.phase == NobodySubscriptions.Phase.CONNECTED ||
            NobodySubscriptions.phase == NobodySubscriptions.Phase.CONNECTING
        if (running) {
            SagerNet.stopService()
            NobodySubscriptions.stop()
            NobodySubscriptions.setPhase(NobodySubscriptions.Phase.DISCONNECTED)
            renderPhase(NobodySubscriptions.Phase.DISCONNECTED)
            return
        }
        if (DataStore.serviceState == BaseService.State.Connected || DataStore.serviceState.canStop) {
            SagerNet.stopService()
            NobodySubscriptions.stop()
            NobodySubscriptions.setPhase(NobodySubscriptions.Phase.DISCONNECTED)
            renderPhase(NobodySubscriptions.Phase.DISCONNECTED)
            return
        }
        startFullFlow()
    }

    private fun startFullFlow() {
        GlobalScope.launch(Dispatchers.Default) {
            NobodySubscriptions.fullRefresh(force = true)
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                renderPhase(NobodySubscriptions.phase)
                refreshServerInfo()
            }
        }
    }

    private fun startSpin() {
        val anim = AnimationUtils.loadAnimation(requireContext(), R.anim.nobody_arc_rotate)
        binding.connectIcon.startAnimation(anim)
    }

    private fun stopSpin() {
        binding.connectIcon.clearAnimation()
    }

    private fun renderPhase(phase: NobodySubscriptions.Phase) {
        when (phase) {
            NobodySubscriptions.Phase.CONNECTED -> {
                stopSpin()
                binding.statusText.text = getString(R.string.nobody_connected)
                binding.connectLabel.text = getString(R.string.nobody_disconnect)
                binding.connectIcon.setImageResource(R.drawable.nobody_ic_check)
                binding.btnConnect.setBackgroundResource(R.drawable.nobody_circle_bg_on)
            }
            NobodySubscriptions.Phase.CONNECTING -> {
                startSpin()
                binding.statusText.text = getString(R.string.nobody_connecting)
                binding.connectLabel.text = getString(R.string.nobody_initializing)
                binding.connectIcon.setImageResource(R.drawable.nobody_ic_sync)
                binding.btnConnect.setBackgroundResource(R.drawable.nobody_circle_bg_loading)
            }
            NobodySubscriptions.Phase.INIT, NobodySubscriptions.Phase.TESTING -> {
                startSpin()
                binding.statusText.text = getString(R.string.nobody_praying)
                binding.connectLabel.text = getString(R.string.nobody_initializing)
                binding.connectIcon.setImageResource(R.drawable.nobody_ic_sync)
                binding.btnConnect.setBackgroundResource(R.drawable.nobody_circle_bg_loading)
            }
            else -> {
                stopSpin()
                binding.statusText.text = getString(R.string.nobody_not_connected)
                binding.connectLabel.text = getString(R.string.nobody_connect_start)
                binding.connectIcon.setImageResource(R.drawable.nobody_ic_power)
                binding.btnConnect.setBackgroundResource(R.drawable.nobody_circle_bg_off)
            }
        }
        binding.progress.visibility =
            if (phase == NobodySubscriptions.Phase.INIT || phase == NobodySubscriptions.Phase.TESTING ||
                phase == NobodySubscriptions.Phase.CONNECTING
            )
                View.VISIBLE else View.GONE
        refreshServerInfo()
    }

    private fun refreshServerInfo() {
        GlobalScope.launch(Dispatchers.Default) {
            val servers = NobodySubscriptions.allServers()
            val selected = NobodySubscriptions.selectedEntity()
            withContext(Dispatchers.Main) {
                _binding ?: return@withContext
                binding.serverCount.text = getString(R.string.nobody_servers_unit, servers.size)
                renderInfoText(selected)
            }
        }
    }

    private fun renderInfoText(selected: Any?) {
        val entity = selected as? io.nekohasekai.sagernet.database.ProxyEntity
        val phase = NobodySubscriptions.phase
        val connected = phase == NobodySubscriptions.Phase.CONNECTED ||
            phase == NobodySubscriptions.Phase.CONNECTING
        when {
            entity == null -> {
                binding.currentServer.text = getString(R.string.nobody_none_selected)
                binding.speedText.text = ""
            }
            connected -> {
                binding.currentServer.text = getString(R.string.nobody_current_server_prefix) +
                    entity.displayName()
                binding.speedText.text = if (entity.ping > 0) entity.ping.toString() + " ms" else ""
            }
            else -> {
                binding.currentServer.text = getString(R.string.nobody_none_selected)
                binding.speedText.text = ""
            }
        }
    }

    fun onServiceStateChanged(state: BaseService.State) {
        if (_binding == null) return
        NobodySubscriptions.syncPhaseFromService()
        renderPhase(NobodySubscriptions.phase)
    }

    fun onSpeedUpdate(tx: Long, rx: Long) {
        if (_binding == null) return
        binding.speedText.text = formatSpeed(tx) + " / " + formatSpeed(rx)
    }

    private fun formatSpeed(bps: Long): String {
        return when {
            bps >= 1024 * 1024 -> String.format("%.1f MB/s", bps / 1024.0 / 1024.0)
            bps >= 1024 -> String.format("%.1f KB/s", bps / 1024.0)
            else -> bps.toString() + " B/s"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        NobodySubscriptions.onPhaseChanged = null
        NobodySubscriptions.onStatusChanged = null
        NobodySubscriptions.onListChanged = null
        _binding = null
    }
}
