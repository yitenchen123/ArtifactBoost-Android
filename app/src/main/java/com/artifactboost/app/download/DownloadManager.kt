package com.artifactboost.app.download

import android.content.Context
import android.net.Uri
import com.artifactboost.app.data.AccelerationSettings
import com.artifactboost.app.data.DownloadItem
import com.artifactboost.app.data.DownloadRoute
import com.artifactboost.app.data.GitHubClient
import com.artifactboost.app.data.GitHubException
import com.artifactboost.app.data.RouteMode
import com.artifactboost.app.data.RouteScope
import com.artifactboost.app.data.ScoredRoute
import com.artifactboost.app.data.SessionManager
import com.artifactboost.app.util.formatSpeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/** 单个下载任务的状态 */
sealed class DownloadState {
    data object Idle : DownloadState()
    data object Resolving : DownloadState()
    data class Downloading(val progress: DownloadProgress) : DownloadState()

    /** 用户主动暂停：半成品与续传账本都保留，点「继续」从断点接着下 */
    data class Paused(val progress: DownloadProgress) : DownloadState()

    /** 达到「同时下载数」上限，排队等前面的完成 */
    data object Queued : DownloadState()

    /** 字节已下完，正在做完整性校验（大压缩包要解一遍 CRC，需要几秒） */
    data object Verifying : DownloadState()
    data class Finished(
        /** 私有暂存目录里的原始文件（引擎直写，多线程落盘用） */
        val file: File,
        /** 系统公共下载目录里的副本（`Download/ArtifactBoost/`），发布失败时为 null */
        val publicUri: Uri? = null,
        /** 展示用相对路径，例如 `Download/ArtifactBoost/xxx.zip` */
        val publicPath: String? = null,
    ) : DownloadState()
    data class Failed(val message: String) : DownloadState()
}

/**
 * 下载任务调度：状态 / 进度 / 取消 / 重试。
 * 对应 iOS 版的 DownloadManager。
 */
class DownloadManager(
    private val appContext: Context,
    private val session: SessionManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val states: StateFlow<Map<String, DownloadState>> = _states.asStateFlow()

    private val _routeSummary = MutableStateFlow<Map<String, String>>(emptyMap())
    val routeSummary: StateFlow<Map<String, String>> = _routeSummary.asStateFlow()

    private val _order = MutableStateFlow<List<String>>(emptyList())
    val order: StateFlow<List<String>> = _order.asStateFlow()

    private val _items = MutableStateFlow<Map<String, DownloadItem>>(emptyMap())
    val items: StateFlow<Map<String, DownloadItem>> = _items.asStateFlow()

    /** 最近一帧进度：暂停时用它渲染「已下 X%，点继续接着下」 */
    private val _lastProgress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val lastProgress: StateFlow<Map<String, DownloadProgress>> = _lastProgress.asStateFlow()

    private val engines = mutableMapOf<String, DownloadEngine>()
    private val jobs = mutableMapOf<String, Job>()

    /** 每次 start 递增：暂停后立刻「继续」时，旧任务的收尾不能误删新一代的引擎/任务引用 */
    private val generations = ConcurrentHashMap<String, Int>()

    /** 下载队列：超过「同时下载数」的任务排在这里，前面的完成 / 取消 / 暂停后自动补位 */
    private val pending = mutableListOf<Pair<DownloadItem, AccelerationSettings>>()

    /** 未完成任务落盘：进程被杀后靠它续下 */
    private val taskStore = DownloadTaskStore(appContext)

    /** 串行 IO：保证「先存后删」的落盘顺序不会乱 */
    private val storage = Dispatchers.IO.limitedParallelism(1)

    /** 引擎落盘用的私有暂存目录；完成后会自动复制一份到系统公共下载目录 */
    val outputDir: File
        get() = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "Artifacts")
            .also { it.mkdirs() }

    // MARK: - 查询

    fun state(item: DownloadItem): DownloadState = _states.value[item.id] ?: DownloadState.Idle

    val activeCount: Int
        get() = _states.value.values.count {
            it is DownloadState.Downloading || it is DownloadState.Resolving ||
                it is DownloadState.Verifying
        }

    val queuedCount: Int
        get() = _states.value.values.count { it is DownloadState.Queued }

    init {
        // 清掉 7 天都没动静的续传残留，避免半成品永久占着磁盘
        ResumeStore.prune()
    }

    val orderedItems: List<DownloadItem>
        get() = _order.value.mapNotNull { _items.value[it] }

    /** 落盘 / 删除都丢到串行 IO 队列，不阻塞主线程，也不怕顺序颠倒 */
    private fun persist(item: DownloadItem, settings: AccelerationSettings) {
        scope.launch(storage) { taskStore.save(item, settings) }
    }

    private fun forget(id: String) {
        scope.launch(storage) { taskStore.remove(id) }
    }

    /**
     * 进程重启后把上次没下完的任务自动续上。
     *
     * 必须在登录态恢复之后调（无 client 时直接返回，记录保留待下次启动）。
     * 地址是临时签名/接口拼出来的，恢复即重新组装，不沿用死时的旧地址。
     */
    fun restorePending() {
        if (session.client.value == null) return
        scope.launch {
            val records = withContext(storage) { taskStore.loadAll() }
            if (records.isEmpty()) return@launch
            // 恢复下载同样要挂着前台服务，否则切后台容易被系统回收
            runCatching { DownloadService.start(appContext) }
            records.forEach { start(it.item, it.settings()) }
        }
    }

    // MARK: - 操作

    fun start(item: DownloadItem, settings: AccelerationSettings) {
        val client = session.client.value ?: return
        when (state(item)) {
            is DownloadState.Resolving, is DownloadState.Downloading,
            is DownloadState.Queued, is DownloadState.Verifying -> return
            else -> Unit
        }

        // 达到「同时下载数」上限：排队，等前面的下完自动开始（IDM 的下载队列）
        if (activeCount >= settings.clampedMaxConcurrent) {
            if (_items.value[item.id] == null) {
                _order.value = listOf(item.id) + _order.value
            }
            _items.value = _items.value + (item.id to item)
            _states.value = _states.value + (item.id to DownloadState.Queued)
            setRouteSummary(item.id, "排队中 · 等待前面的下载完成")
            persist(item, settings)
            if (pending.none { it.first.id == item.id }) {
                pending.add(item to settings)
            }
            return
        }

        if (_items.value[item.id] == null) {
            _order.value = listOf(item.id) + _order.value
        }
        _items.value = _items.value + (item.id to item)
        _states.value = _states.value + (item.id to DownloadState.Resolving)
        _routeSummary.value = _routeSummary.value - item.id
        // 先落盘再开工：中途进程被杀，下次启动能按这份记录续下
        persist(item, settings)

        val engine = DownloadEngine()
        engines[item.id] = engine
        val generation = (generations[item.id] ?: 0) + 1
        generations[item.id] = generation

        jobs[item.id] = scope.launch {
            try {
                val file = performDownload(item, client, engine, settings, generation)
                // 默认保存到安卓系统公共目录：私有暂存完成后复制一份到
                // Download/ArtifactBoost/（10+ 走 MediaStore，无需权限；7~9 直写）。
                // 发布失败也不影响本次下载，保留私有文件兜底。
                val published = withContext(Dispatchers.IO) {
                    runCatching { PublicDownloads.publish(appContext, file) }.getOrNull()
                }
                // 校验/发布期间用户可能已经取消：别再把它标成「完成」
                coroutineContext.ensureActive()
                // 用户也可能在下完的瞬间点「移除」：列表里已经没有这一项，别留下幽灵状态
                if (_items.value[item.id] != null) {
                    if (published != null) {
                        val prev = _routeSummary.value[item.id]
                        val saved = "已保存到 ${published.displayPath}"
                        setRouteSummary(item.id, if (prev.isNullOrBlank()) saved else "$prev · $saved")
                    }
                    _states.value = _states.value + (
                        item.id to DownloadState.Finished(file, published?.uri, published?.displayPath)
                        )
                    _lastProgress.value = _lastProgress.value - item.id
                }
                forget(item.id)
            } catch (e: Exception) {
                // 任务已被移除时只做收尾，不再写状态
                if (_items.value[item.id] != null) {
                    if (isCancellation(e)) {
                        // 取消有两种：用户点了暂停（状态已经是 Paused，别覆盖），
                        // 以及用户点了取消（回 Idle）
                        if (_states.value[item.id] !is DownloadState.Paused) {
                            _states.value = _states.value + (item.id to DownloadState.Idle)
                        }
                    } else {
                        _states.value = _states.value + (item.id to DownloadState.Failed(e.message ?: "下载失败"))
                        _routeSummary.value = _routeSummary.value - item.id
                        // 失败不自动续下：清记录，用户手动重试时会重新落盘
                        forget(item.id)
                    }
                }
            } finally {
                // 只有「还是这一代任务」才清理；否则会把用户刚点「继续」新建的那一代误删
                if (generations[item.id] == generation) {
                    generations.remove(item.id)
                    engines.remove(item.id)
                    jobs.remove(item.id)
                }
                // 让出一个名额：队列里的下一个自动开始
                drainQueue()
            }
        }
    }

    /** 暂停：掐断连接但**保留**半成品与续传账本，点「继续」从断点接着下 */
    fun pause(item: DownloadItem) {
        val progress = _lastProgress.value[item.id] ?: DownloadProgress()
        _states.value = _states.value + (item.id to DownloadState.Paused(progress))
        engines[item.id]?.cancel()
        jobs[item.id]?.cancel()
        jobs.remove(item.id)
        engines.remove(item.id)
        _routeSummary.value = _routeSummary.value - item.id
        removeFromQueue(item.id)
        drainQueue()
        // 记录保留：进程重启后仍能自动续上
    }

    /** 从队列里补一个任务进来（一次只补一个：每次调用都对应一个被让出的名额） */
    private fun drainQueue() {
        val next = pending.firstOrNull { state(it.first) is DownloadState.Queued } ?: return
        pending.remove(next)
        // 队列里补位时前台服务可能已经停了（上一个任务刚结束），重新拉起，
        // 否则补位的下载会在没有前台服务的情况下跑，容易被系统回收。
        runCatching { DownloadService.start(appContext) }
        // start 会把状态推到 Resolving；先复位，免得被自己的 Queued 守卫挡回来
        _states.value = _states.value + (next.first.id to DownloadState.Idle)
        start(next.first, next.second)
    }

    private fun removeFromQueue(id: String) {
        pending.removeAll { it.first.id == id }
    }

    /**
     * 用户把「同时下载数」调大后，立刻把空出来的名额补满。
     * 否则要等某个正在下载的任务结束，队列才会动 —— 看起来像设置没生效。
     */
    fun fillQueueSlots() {
        val limit = AccelerationSettings.load(appContext).clampedMaxConcurrent
        while (activeCount < limit) {
            val before = activeCount
            drainQueue()
            if (activeCount == before) return
        }
    }

    fun cancel(item: DownloadItem) {
        engines[item.id]?.cancel()
        jobs[item.id]?.cancel()
        jobs.remove(item.id)
        engines.remove(item.id)
        // 用户主动取消：连同半成品一起清掉，不恢复
        ResumeStore.discard(item.id)
        forget(item.id)
        _lastProgress.value = _lastProgress.value - item.id
        _states.value = _states.value + (item.id to DownloadState.Idle)
        removeFromQueue(item.id)
        drainQueue()
    }

    fun remove(item: DownloadItem) {
        engines[item.id]?.cancel()
        jobs[item.id]?.cancel()
        engines.remove(item.id)
        jobs.remove(item.id)
        ResumeStore.discard(item.id)
        forget(item.id)
        _lastProgress.value = _lastProgress.value - item.id
        _states.value = _states.value - item.id
        _routeSummary.value = _routeSummary.value - item.id
        _items.value = _items.value - item.id
        _order.value = _order.value - item.id
        removeFromQueue(item.id)
        drainQueue()
    }

    fun clearFinished() {
        _order.value.forEach { id ->
            when (_states.value[id]) {
                is DownloadState.Finished, is DownloadState.Failed, null -> {
                    _items.value[id]?.let { remove(it) }
                }
                else -> Unit
            }
        }
    }

    private companion object {

        /** 失败重试前的退避：抖动网络下立刻重打大概率再撞上，歇一下更划算 */
        const val RETRY_DELAY_MS = 800L
    }

    // MARK: - 下载主流程

    /**
     * IDM 式的下载流程：**没有「解析地址」这一阶段**。
     *
     * 第一个带 Range 的请求直接打到「github.com 稳定地址 / codeload / api.github.com」，
     * 由引擎在同一个往返里完成：跟随 302 拿签名地址、读出体积、拿回首块数据。
     * 用户点下去到出速度只差一个 RTT，而不是老实现那样排四段队。
     */
    private suspend fun performDownload(
        item: DownloadItem,
        client: GitHubClient,
        engine: DownloadEngine,
        settings: AccelerationSettings,
        generation: Int,
    ): File {
        val request = makeRequest(item, client, settings)
        var lastError: Throwable = DownloadException.BadResponse
        for (attempt in 0 until 2) {
            try {
                if (attempt > 0) {
                    setRouteSummary(item.id, "网络抖动，正在重试…")
                    delay(RETRY_DELAY_MS)
                }
                // 上一次可能是「校验失败」结束的，重试前把状态拉回活动态，
                // 否则进度回调会因为「不是 Resolving/Downloading」被自己挡掉。
                // 只在仍是 Verifying 时复位，避免把用户刚点的「暂停」覆盖掉。
                if (_states.value[item.id] is DownloadState.Verifying) {
                    _states.value = _states.value + (item.id to DownloadState.Resolving)
                }
                val result = engine.download(request, outputDir) progress@{ progress ->
                    // 只接受「本代任务」且仍在活动状态时的进度。
                    // 否则取消 / 暂停后仍在途的最后一拍进度会把状态又推回「下载中」。
                    if (generations[item.id] != generation) return@progress
                    val current = _states.value[item.id]
                    if (current !is DownloadState.Resolving && current !is DownloadState.Downloading) {
                        return@progress
                    }
                    _lastProgress.value = _lastProgress.value + (item.id to progress)
                    _states.value = _states.value + (item.id to DownloadState.Downloading(progress))
                }
                // 长度对不代表内容对：压缩包再验一次结构/CRC，坏包当场重试。
                // 大压缩包要解一遍算 CRC，明确亮出「校验中」，别让界面像卡住了。
                _states.value = _states.value + (item.id to DownloadState.Verifying)
                val reason = withContext(Dispatchers.IO) { ArtifactVerifier.verify(result.file) }
                if (reason != null) {
                    runCatching { result.file.delete() }
                    throw DownloadException.VerificationFailed(reason)
                }
                // 校验期间用户可能已经点了取消：别再把它标成「完成」
                coroutineContext.ensureActive()
                setRouteSummary(item.id, "${describe(request.routes)} · 平均 ${formatSpeed(result.averageSpeed)}")
                return result.file
            } catch (e: Exception) {
                if (isCancellation(e)) throw e
                lastError = e
                if (!shouldRetry(e)) throw e
            }
        }
        throw lastError
    }

    /** 组装引擎输入：**全程零网络请求**，地址是拼出来的，不是问出来的 */
    private fun makeRequest(
        item: DownloadItem,
        client: GitHubClient,
        settings: AccelerationSettings,
    ): DownloadRequest {
        val start = client.startUrl(item.source, item.isPrivate)
        val stableUrl = item.source.ghfastEligibleUrl
        val candidates = settings.candidateRoutes(item.isPrivate, stableUrl)

        // 探测结果只做「锦上添花」：有缓存直接用（0 延迟），
        // 没缓存也绝不为了排序把开始时间往后推 —— 引擎会边下边学。
        var plan: List<ScoredRoute> = candidates.map { ScoredRoute(it, 1.0) }
        val host = runCatching { java.net.URI(start.url).host }.getOrNull()
        val cached = host?.let { RouteProbeCache.cached(it) }
        if (candidates.size > 1 && cached != null) {
            val names = candidates.map { it.name }.toSet()
            plan = planFromProbes(cached.filter { it.route.name in names }, candidates)
        } else if (candidates.size > 1 && item.source.supportsChunkedDownload) {
            warmProbe(start.url, candidates, stableUrl)
        }

        return DownloadRequest(
            startUrl = start.url,
            stableUrl = stableUrl,
            routes = plan,
            authToken = client.token,
            startHeaders = start.headers,
            fileName = item.fileName,
            connections = settings.clampedConnections,
            allowChunking = item.source.supportsChunkedDownload,
            resumeKey = item.id,
            expectedSize = item.size,
        )
    }

    /** 后台预热通道探测：不阻塞本次下载，只为下一次（或下一个附件）省掉试错 */
    private fun warmProbe(startUrl: String, routes: List<DownloadRoute>, stableUrl: String?) {
        val host = runCatching { java.net.URI(startUrl).host }.getOrNull() ?: return
        val urls = routes.map { route ->
            when (route.scope) {
                RouteScope.ANY -> route.apply(startUrl)
                RouteScope.GITHUB_ONLY -> route.apply(stableUrl ?: startUrl)
            }
        }
        scope.launch(Dispatchers.IO) {
            runCatching {
                val probes = RouteProbeCache.probe(routes, urls)
                if (probes.any { it.ok }) RouteProbeCache.store(probes, host)
            }
        }
    }

    /**
     * 把探测结果映射成「带初始权重的通道计划」。
     *
     * 规则：
     *  - 探测可用的通道按实测速度给权重（引擎一开始就把活儿压到真正的快线上）；
     *  - 探测不可用的通道剔除，但**直连永远保留**（引擎的最终兜底依赖它）；
     *  - 全部不可用时保留原样，让引擎自己兜底。
     */
    private fun planFromProbes(
        probes: List<RouteProbeResult>,
        candidates: List<DownloadRoute>,
    ): List<ScoredRoute> {
        if (probes.isEmpty()) return candidates.map { ScoredRoute(it, 1.0) }
        val usable = probes.filter { it.ok }.toMutableList()
        // 直连必须留在计划里（兜底通道）
        if (usable.none { it.route.isDirect }) {
            probes.firstOrNull { it.route.isDirect }?.let { usable.add(it) }
        }
        val source = if (usable.isEmpty()) probes else usable
        return source.map { ScoredRoute(it.route, maxOf(it.speed, 1.0)) }
    }

    private fun setRouteSummary(id: String, text: String) {
        _routeSummary.value = _routeSummary.value + (id to text)
    }

    /** 通道描述，例如「多通道 gh-proxy.com + slink.ltd」 */
    private fun describe(plan: List<ScoredRoute>): String {
        val names = plan.map { if (it.route.isDirect) "直连" else it.route.name }
        return if (plan.size > 1) "多通道 " + names.joinToString(" + ") else names[0]
    }

    private fun isCancellation(error: Throwable): Boolean =
        error is DownloadException.Cancelled ||
            error is kotlinx.coroutines.CancellationException

    /** 只有网络类错误才值得重试；权限、产物已删除等错误直接抛出 */
    private fun shouldRetry(error: Throwable): Boolean {
        if (isCancellation(error)) return false
        val ghError = error as? GitHubException ?: return true
        return when (ghError) {
            is GitHubException.Http -> ghError.code >= 500 || ghError.code == 429
            is GitHubException.BadResponse -> true
            GitHubException.ArtifactExpired,
            GitHubException.DownloadUrlNotFound,
            is GitHubException.BadUrl -> false
        }
    }
}
