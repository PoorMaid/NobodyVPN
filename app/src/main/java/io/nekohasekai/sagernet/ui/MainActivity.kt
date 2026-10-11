package io.nekohasekai.sagernet.ui

import android.Manifest.permission.POST_NOTIFICATIONS
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import androidx.activity.addCallback
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceDataStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.nobody.NobodyLog
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.databinding.LayoutMainNobodyBinding
import io.nekohasekai.sagernet.group.GroupInterfaceAdapter
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher

class MainActivity : ThemedActivity(), SagerConnection.Callback,
    OnPreferenceDataStoreChangeListener {

    lateinit var binding: LayoutMainNobodyBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        NobodyLog.checkpoint("MainActivity.onCreate start")
        super.onCreate(savedInstanceState)
        NobodyLog.checkpoint("MainActivity.afterSuperCreate")
        binding = LayoutMainNobodyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    showFragment(HomeFragment())
            NobodyLog.checkpoint("MainActivity.showHomeFragment")
            true
                }
                R.id.nav_connect -> {
                    showFragment(ConnectFragment())
                    true
                }
                else -> false
            }
        }

        if (savedInstanceState == null) {
            binding.bottomNav.selectedItemId = R.id.nav_home
        }

        showNobodyNotice()

        onBackPressedDispatcher.addCallback(this) {
            val f = supportFragmentManager.findFragmentById(R.id.fragment_holder)
            if (f !is HomeFragment) {
                binding.bottomNav.selectedItemId = R.id.nav_home
            } else {
                moveTaskToBack(false)
            }
        }

        connection.connect(this, this)
        GroupManager.userInterface = GroupInterfaceAdapter(this)
        DataStore.configurationStore.registerChangeListener(this)

        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(this, arrayOf(POST_NOTIFICATIONS), 1)
            }
        }
    }

    private fun showNobodyNotice() {
        val sp = getSharedPreferences("nobody_notice", MODE_PRIVATE)
        if (sp.getBoolean("dont_show", false)) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.nobody_notice_title)
            .setMessage(R.string.nobody_notice_msg)
            .setNegativeButton(R.string.nobody_notice_known) { d, _ -> d.dismiss() }
            .setPositiveButton(R.string.nobody_notice_nomore) { d, _ ->
                sp.edit().putBoolean("dont_show", true).apply()
                d.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    @SuppressLint("CommitTransaction")
    fun showFragment(fragment: androidx.fragment.app.Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_holder, fragment)
            .commitAllowingStateLoss()
    }

    private fun changeState(state: BaseService.State, msg: String? = null) {
        DataStore.serviceState = state
        if (msg != null) snackbar(getString(R.string.vpn_error, msg)).show()
        val f = supportFragmentManager.findFragmentById(R.id.fragment_holder)
        if (f is ConnectFragment) f.onServiceStateChanged(state)
    }

    override fun snackbarInternal(text: CharSequence): Snackbar {
        return Snackbar.make(binding.coordinator, text, Snackbar.LENGTH_LONG)
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
        changeState(state, msg)
    }

    val connection = SagerConnection(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND, true)

    override fun onServiceConnected(service: ISagerNetService) = changeState(
        try {
            BaseService.State.values()[service.state]
        } catch (_: RemoteException) {
            BaseService.State.Idle
        }
    )

    override fun onServiceDisconnected() = changeState(BaseService.State.Idle)

    override fun onBinderDied() {
        connection.disconnect(this)
        connection.connect(this, this)
    }

    override fun cbSpeedUpdate(stats: SpeedDisplayData) {
        val f = supportFragmentManager.findFragmentById(R.id.fragment_holder)
        if (f is ConnectFragment) f.onSpeedUpdate(stats.txRateProxy, stats.rxRateProxy)
    }

    override fun cbTrafficUpdate(data: TrafficData) {
        runOnDefaultDispatcher {
            ProfileManager.postUpdate(data)
        }
    }

    override fun cbSelectorUpdate(id: Long) {
        val old = DataStore.selectedProxy
        DataStore.selectedProxy = id
        DataStore.currentProfile = id
        runOnDefaultDispatcher {
            ProfileManager.postUpdate(old, true)
            ProfileManager.postUpdate(id, true)
        }
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        when (key) {
            "service_mode" -> onBinderDied()
            "proxy_apps", "bypass_mode", "individual" -> {
                if (DataStore.serviceState.canStop) {
                    snackbar(getString(R.string.need_reload)).setAction(R.string.apply) {
                        SagerNet.reloadService()
                    }.show()
                }
            }
        }
    }

    override fun onStart() {
        connection.updateConnectionId(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND)
        super.onStart()
    }

    override fun onStop() {
        connection.updateConnectionId(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND)
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        GroupManager.userInterface = null
        DataStore.configurationStore.unregisterChangeListener(this)
        connection.disconnect(this)
    }

}
