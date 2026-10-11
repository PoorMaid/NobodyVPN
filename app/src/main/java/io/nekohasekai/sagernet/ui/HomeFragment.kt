package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.databinding.FragmentNobodyHomeBinding
import io.nekohasekai.sagernet.nobody.NobodyLog

class HomeFragment : Fragment() {

    private var _binding: FragmentNobodyHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentNobodyHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        NobodyLog.checkpoint("HomeFragment.onViewCreated")
        binding.version.text = getString(R.string.app_name) + "  v" + BuildConfig.VERSION_NAME

        binding.rowWebsite.setOnClickListener {
            openExternal("https://nobady.bbroot.com")
        }
        binding.rowGithub.setOnClickListener {
            openExternal("https://github.com/PoorMaid/NobodyVPN")
        }
        binding.rowEmail.setOnClickListener {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:tyxypoor@outlook.com")
            }
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "tyxypoor@outlook.com", Toast.LENGTH_LONG).show()
            }
        }
        binding.btnNekobox.setOnClickListener {
            openExternal("https://github.com/MatsuriDayo/NekoBoxForAndroid")
        }
        binding.btnAntilost.setOnClickListener {
            startActivity(Intent(requireContext(), AntiLostActivity::class.java))
        }
        updateLogButton()
        binding.btnNobodyLog.setOnClickListener {
            try {
                NobodyLog.checkpoint("btnNobodyLog clicked")
                if (NobodyLog.isEnabled()) {
                    NobodyLog.stop()
                    Toast.makeText(requireContext(), R.string.nobody_log_stopped, Toast.LENGTH_SHORT).show()
                } else {
                    NobodyLog.init(requireContext().filesDir)
                    NobodyLog.start()
                    NobodyLog.i("UI", "log enabled by user")
                    Toast.makeText(requireContext(), R.string.nobody_log_started, Toast.LENGTH_SHORT).show()
                }
                updateLogButton()
            } catch (e: Throwable) {
                NobodyLog.checkpoint("btnNobodyLog ERROR: ${e.javaClass.simpleName}: ${e.message}")
                Toast.makeText(requireContext(), "日志出错: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateLogButton() {
        binding.btnNobodyLog.text = getString(
            if (NobodyLog.isEnabled()) R.string.nobody_log_disable else R.string.nobody_log_enable
        )
    }

    private fun openExternal(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), url, Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
