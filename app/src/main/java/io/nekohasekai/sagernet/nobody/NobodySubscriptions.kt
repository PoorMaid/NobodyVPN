package io.nekohasekai.sagernet.nobody

import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.bg.proto.UrlTest
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import libcore.Libcore
import moe.matsuri.nb4a.utils.Util
import java.util.concurrent.atomic.AtomicInteger

object NobodySubscriptions {

    const val SUB_1_LINK =
        "https://raw.githubusercontent.com/WLget/V2Ray_configs_64/refs/heads/master/ConfigSub_list.txt"


    const val GROUP_1_NAME = "Nobody-订阅1"
    const val MAIN_GROUP_NAME = "Nobody-主池"

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

    const val STATUS_UNKNOWN = 0
    const val STATUS_AVAILABLE = 1
    const val STATUS_DEAD = 2

    private const val DEAD_THRESHOLD = 3

    private val failCounts = HashMap<String, Int>()
    private val deadKeys = HashSet<String>()

    enum class Phase { IDLE, INIT, TESTING, CONNECTING, CONNECTED, DISCONNECTED }

    var phase: Phase = Phase.IDLE
        private set

    var onPhaseChanged: ((Phase) -> Unit)? = null
    var onStatusChanged: ((String) -> Unit)? = null
    var onListChanged: (() -> Unit)? = null

    private val mutex = Mutex()
    private var running: Job? = null

    fun setPhase(p: Phase) {
        phase = p
        runOnMainDispatcher { onPhaseChanged?.invoke(p) }
    }

    private fun setStatus(s: String) {
        runOnMainDispatcher { onStatusChanged?.invoke(s) }
    }

    private fun notifyList() {
        runOnMainDispatcher { onListChanged?.invoke() }
    }

    private fun dedupKeyOf(entity: ProxyEntity): String {
        val bean = entity.requireBean()
        return bean.dedupKey()
    }

    private fun AbstractBean.dedupKey(): String {
        val sb = StringBuilder()
        sb.append(this.javaClass.simpleName).append("://")
        sb.append(serverAddress).append(":").append(serverPort)
        when (this) {
            is io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean -> {
                if (!uuid.isNullOrBlank()) sb.append("#").append(uuid)
            }
            is io.nekohasekai.sagernet.fmt.trojan.TrojanBean -> {
                if (!password.isNullOrBlank()) sb.append("#").append(password)
            }
            is io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean -> {
                if (!password.isNullOrBlank()) sb.append("#").append(method).append("#").append(password)
            }
            is io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean -> {
                if (!authPayload.isNullOrBlank()) sb.append("#").append(authPayload)
            }
            else -> {
                if (!name.isNullOrBlank()) sb.append("#").append(name)
            }
        }
        return sb.toString()
    }

    private suspend fun fetchPlain(link: String): String {
        val response = Libcore.newHttpClient().apply {
            tryH3Direct()
        }.newRequest().apply {
            setURL(link)
            setUserAgent(USER_AGENT)
        }.execute()
        return Util.getStringBox(response.contentString)
    }

    private suspend fun purgeLegacyGroups() {
        val subs = SagerDatabase.groupDao.subscriptions()
        for (g in subs) {
            if (g.name != MAIN_GROUP_NAME) {
                try {
                    SagerDatabase.proxyDao.deleteByGroup(g.id)
                    SagerDatabase.groupDao.deleteGroup(g)
                } catch (e: Exception) {
                    Logs.d("purge group failed: ${g.name} ${e.readableMessage}")
                }
            }
        }
    }
    private suspend fun ensureGroup(name: String, link: String): ProxyGroup {
        val subs = SagerDatabase.groupDao.subscriptions()
        subs.firstOrNull { it.name == name }?.let { return it }
        val group = ProxyGroup(
            name = name,
            type = GroupType.SUBSCRIPTION,
            subscription = SubscriptionBean().apply {
                this.link = link
                type = 0
                forceResolve = false
                deduplication = true
            }
        )
        return GroupManager.createGroup(group)
    }

    private suspend fun importBeans(group: ProxyGroup, beans: List<AbstractBean>) {
        var order = (SagerDatabase.proxyDao.nextOrder(group.id) ?: 1L)
        val exists = SagerDatabase.proxyDao.getByGroup(group.id)
        val existsKeys = HashSet<String>()
        exists.forEach { existsKeys.add(dedupKeyOf(it)) }
        for (bean in beans) {
            val key = bean.dedupKey()
            if (existsKeys.contains(key) || deadKeys.contains(key)) continue
            val entity = ProxyEntity(groupId = group.id, userOrder = order).apply {
                putBean(bean)
                status = STATUS_UNKNOWN
            }
            SagerDatabase.proxyDao.addProxy(entity)
            existsKeys.add(key)
            order++
        }
        val group2 = SagerDatabase.groupDao.getById(group.id)
        if (group2 != null) {
            group2.subscription?.lastUpdated = (System.currentTimeMillis() / 1000).toInt()
            SagerDatabase.groupDao.updateGroup(group2)
        }
        notifyList()
    }

    private suspend fun loadSubscription(listUrl: String, groupName: String, expandSecondLayer: Boolean, targetGroupName: String = groupName) {
        val group = ensureGroup(targetGroupName, listUrl)
        setStatus("拉取 $groupName ...")
        val text = fetchPlain(listUrl)
        val lines = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()

        val beans = ArrayList<AbstractBean>()
        if (expandSecondLayer) {
            val subLinks = lines.filter { it.startsWith("http://") || it.startsWith("https://") }
            setStatus("展开 $groupName 子订阅 (${subLinks.size}) ...")
            val index = AtomicInteger(0)
            coroutineScope {
                val jobs = subLinks.map { sub ->
                    async(Dispatchers.IO) {
                        try {
                            val subText = fetchPlain(sub)
                            val parsed = RawUpdater.parseRaw(subText)
                            if (parsed != null) {
                                synchronized(beans) { beans.addAll(parsed) }
                            }
                        } catch (e: Exception) {
                            Logs.d("sub expand failed: ${sub} ${e.readableMessage}")
                        }
                        val done = index.incrementAndGet()
                        if (done % 5 == 0) setStatus("展开 $groupName 子订阅 $done/${subLinks.size} ...")
                    }
                }
                jobs.awaitAll()
            }
        } else {
            val parsed = RawUpdater.parseRaw(text)
            if (parsed != null) beans.addAll(parsed)
        }

        setStatus("$groupName 解析到 ${beans.size} 个节点")
        importBeans(group, beans)
    }

    suspend fun fullRefresh(force: Boolean = false) {
        mutex.withLock {
            if (!force && running?.isActive == true) return
            val prev = running
            running = GlobalScope.launch(Dispatchers.Default) {
                prev?.cancelAndJoin()
                try {
                    setPhase(Phase.INIT)
                    setStatus("初始化 ...")
                    purgeLegacyGroups()
                    loadSubscription(SUB_1_LINK, GROUP_1_NAME, expandSecondLayer = false, targetGroupName = MAIN_GROUP_NAME)
                    val main = SagerDatabase.groupDao.subscriptions().firstOrNull { it.name == MAIN_GROUP_NAME }
                    if (main == null) {
                        setStatus("无可用节点")
                        setPhase(Phase.DISCONNECTED)
                        return@launch
                    }
                    setStatus("合并去重 ...")
                    dedupAll()
                    setStatus("测试真连接 ...")
                    val pool = SagerDatabase.proxyDao.getByGroup(main.id)
                    val testResult = testUrlBatch(pool)
                    val best = testResult.filter { it.alive }.minByOrNull { it.e.ping }
                    setStatus("选出最优节点 ...")
                    if (best != null) {
                        switchAndConnect(best.e)
                    } else {
                        setStatus("无可用节点")
                        setPhase(Phase.DISCONNECTED)
                    }
                } catch (e: Throwable) {
                    Logs.w(e)
                    setStatus("刷新失败: ${e.readableMessage}")
                    setPhase(Phase.DISCONNECTED)
                }
            }
        }
    }
    private suspend fun pingEntity(entity: ProxyEntity): Boolean {
        val delay = try {
            UrlTest().doTest(entity)
        } catch (e: Exception) {
            -1
        }
        val ok = delay >= 0
        entity.ping = if (ok) delay.coerceAtLeast(1) else 0
        return ok
    }
    data class UrlTestResult(val e: ProxyEntity, val alive: Boolean)
    suspend fun testUrlBatch(list: List<ProxyEntity>): List<UrlTestResult> {
        val concurrency = DataStore.connectionTestConcurrent.coerceAtLeast(1)
        val results = java.util.Collections.synchronizedList(ArrayList<UrlTestResult>())
        val sem = kotlinx.coroutines.sync.Semaphore(concurrency)
        val total = list.size
        val done = AtomicInteger(0)
        coroutineScope {
            list.map { entity ->
                async(Dispatchers.IO) {
                    sem.acquire()
                    try {
                        val ok = pingEntity(entity)
                        handleTestResult(entity, ok)
                        results.add(UrlTestResult(entity, ok))
                    } catch (e: Exception) {
                        Logs.d("urltest failed: ${e.readableMessage}")
                    } finally {
                        sem.release()
                        val d = done.incrementAndGet()
                        if (d % 5 == 0 || d == total) setStatus("测试 $d/$total ...")
                    }
                }
            }.awaitAll()
        }
        notifyList()
        return results.toList()
    }
    private suspend fun handleTestResult(entity: ProxyEntity, ok: Boolean) {
        val key = dedupKeyOf(entity)
        if (ok) {
            entity.status = STATUS_AVAILABLE
            failCounts.remove(key)
            SagerDatabase.proxyDao.updateProxy(entity)
        } else {
            val c = (failCounts[key] ?: 0) + 1
            failCounts[key] = c
            if (c >= DEAD_THRESHOLD) {
                deadKeys.add(key)
                entity.status = STATUS_DEAD
                SagerDatabase.proxyDao.updateProxy(entity)
            } else {
                SagerDatabase.proxyDao.updateProxy(entity)
            }
        }
    }


    suspend fun pickBest(groupId: Long?): ProxyEntity? {
        val all = if (groupId != null) {
            SagerDatabase.proxyDao.getByGroup(groupId)
        } else {
            val subs = SagerDatabase.groupDao.subscriptions()
            subs.flatMap { SagerDatabase.proxyDao.getByGroup(it.id) }
        }
        val alive = all.filter { it.status != STATUS_DEAD }
        val withPing = alive.filter { it.ping > 0 }
        if (withPing.isNotEmpty()) return withPing.minByOrNull { it.ping }
        return alive.firstOrNull()
    }

    suspend fun dedupAll() {
        val subs = SagerDatabase.groupDao.subscriptions()
        val all = subs.flatMap { SagerDatabase.proxyDao.getByGroup(it.id) }
        val byKey = LinkedHashMap<String, MutableList<ProxyEntity>>()
        for (e in all) {
            byKey.getOrPut(dedupKeyOf(e)) { ArrayList() }.add(e)
        }
        val toDelete = ArrayList<ProxyEntity>()
        for ((_, list) in byKey) {
            if (list.size <= 1) continue
            val keep = list.last()
            var rx = keep.rx
            var tx = keep.tx
            for (i in 0 until list.size - 1) {
                val old = list[i]
                rx += old.rx
                tx += old.tx
                toDelete.add(old)
            }
            keep.rx = rx
            keep.tx = tx
            SagerDatabase.proxyDao.updateProxy(keep)
        }
        if (toDelete.isNotEmpty()) {
            SagerDatabase.proxyDao.deleteProxy(toDelete)
            notifyList()
        }
    }

    suspend fun switchAndConnect(entity: ProxyEntity) {
        setPhase(Phase.CONNECTING)
        DataStore.selectedProxy = entity.id
        DataStore.selectedGroup = entity.groupId
        ProfileManager.postUpdate(entity)
        if (DataStore.serviceState.canStop) {
            SagerNet.reloadService()
        } else {
            SagerNet.startService()
        }
    }

    suspend fun switchTo(entity: ProxyEntity) {
        DataStore.selectedProxy = entity.id
        DataStore.selectedGroup = entity.groupId
        ProfileManager.postUpdate(entity)
        if (DataStore.serviceState.canStop) {
            SagerNet.reloadService()
        }
    }

    suspend fun reloadForAppProxyChanged() {
        if (DataStore.serviceState.canStop) {
            SagerNet.reloadService()
        }
    }

    fun stop() {
        SagerNet.stopService()
        setPhase(Phase.DISCONNECTED)
    }

    suspend fun allServers(): List<ProxyEntity> {
        val subs = SagerDatabase.groupDao.subscriptions()
        val main = subs.firstOrNull { it.name == MAIN_GROUP_NAME }
        if (main != null) return SagerDatabase.proxyDao.getByGroup(main.id)
        return subs.flatMap { SagerDatabase.proxyDao.getByGroup(it.id) }
    }

    suspend fun selectedEntity(): ProxyEntity? {
        val id = DataStore.selectedProxy
        if (id <= 0) return null
        return SagerDatabase.proxyDao.getById(id)
    }

    suspend fun allServersSorted(): List<ProxyEntity> {
        val list = allServers().toMutableList()
        list.sortWith(compareBy({ it.status == STATUS_DEAD }, { if (it.ping > 0) it.ping else Int.MAX_VALUE }))
        return list
    }

    fun syncPhaseFromService() {
        if (DataStore.serviceState.connected) {
            setPhase(Phase.CONNECTED)
        } else if (!DataStore.serviceState.started) {
            if (phase == Phase.CONNECTED || phase == Phase.CONNECTING) setPhase(Phase.DISCONNECTED)
        }
    }
}
