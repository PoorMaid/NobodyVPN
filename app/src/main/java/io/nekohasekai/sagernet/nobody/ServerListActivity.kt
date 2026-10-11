package io.nekohasekai.sagernet.nobody

import android.os.Bundle
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.SystemClock
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.databinding.ActivityNobodyServerListBinding
import io.nekohasekai.sagernet.ktx.isIpAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

class ServerListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNobodyServerListBinding
    private lateinit var adapter: ServerListAdapter
    private var testJob: Job? = null
    private var testAnim: ObjectAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNobodyServerListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = ServerListAdapter { entity ->
            onServerClicked(entity)
        }
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnTestAll.setOnClickListener { runPingTest() }
        testAnim = ObjectAnimator.ofFloat(binding.btnTestAll, "alpha", 1f, 0.35f).apply {
            duration = 700
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }
        binding.swipeRefresh.setOnRefreshListener {
            binding.swipeRefresh.isRefreshing = false
            refreshList()
        }

        NobodySubscriptions.onListChanged = { runOnUiThread { refreshList() } }
        NobodySubscriptions.onStatusChanged = { status -> runOnUiThread { binding.toolbar.subtitle = status } }

        refreshList()
    }

    override fun onDestroy() {
        testJob?.cancel()
        NobodySubscriptions.onListChanged = null
        NobodySubscriptions.onStatusChanged = null
        super.onDestroy()
    }

    private fun refreshList() {
        lifecycleScope.launch(Dispatchers.IO) {
            val list = NobodySubscriptions.allServersSorted()
            val selected = DataStore.selectedProxy
            withContext(Dispatchers.Main) {
                adapter.submit(list, selected)
                binding.emptyView.visibility = if (list.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
                binding.toolbar.subtitle = resources.getString(R.string.nobody_servers_unit, list.size)
            }
        }
    }

    private fun onServerClicked(entity: ProxyEntity) {
        adapter.setSelected(entity.id)
        binding.toolbar.subtitle = entity.displayName()
        lifecycleScope.launch {
            NobodySubscriptions.switchAndConnect(entity)
            finish()
        }
    }

    private fun runPingTest() {
        if (DataStore.runningTest) return
        DataStore.runningTest = true
        binding.btnTestAll.isEnabled = false
        binding.btnTestAll.text = getString(R.string.nobody_testing)
        testAnim?.start()
        testJob = lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { NobodySubscriptions.allServersSorted() }
            withContext(Dispatchers.IO) {
                NobodySubscriptions.testUrlBatch(list)
            }
            withContext(Dispatchers.Main) {
                testAnim?.cancel()
                binding.btnTestAll.alpha = 1f
                binding.btnTestAll.text = getString(R.string.nobody_test_speed)
                binding.btnTestAll.isEnabled = true
                DataStore.runningTest = false
                refreshList()
            }
        }
    }
}
