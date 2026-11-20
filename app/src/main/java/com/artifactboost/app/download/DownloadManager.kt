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
import kotlin.coroutines.coroutineContext

/** 单个下载任务的状态 */
sealed class DownloadState {
    data object Idle : DownloadState()
    data object Resolving : DownloadState()
    data class Downloading(val progress: DownloadProgress) : DownloadState()

    /** 用户主动暂停：半成品与续传账本都保留，点「继续」从断点接着下 */
    data class Paused(val progress: DownloadProgress) : DownloadState()
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
    private val generations = mutableMapOf<String, Int>()

    /** 引擎落盘用的私有暂存目录；完成后会自动复制一份到系统公共下载目录 */
    val outputDir: File
        get() = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "Artifacts")
            .also { it.mkdirs() }

    // MARK: - 查询

    fun state(item: DownloadItem): DownloadState = _states.value[item.id] ?: DownloadState.Idle

    val activeCount: Int
        get() = _states.value.values.count {
            it is DownloadState.Downloading || it is DownloadState.Resolving
        }

    val orderedItems: List<DownloadItem>
        get() = _order.value.mapNotNull { _items.value[it] }

    // MARK: - 操作

    fun start(item: DownloadItem, settings: AccelerationSettings) {
        val client = session.client.value ?: return
        when (state(item)) {
            is DownloadState.Resolving, is DownloadState.Downloading -> return
            else -> Unit
        }

        if (_items.value[item.id] == null) {
            _order.value = listOf(item.id) + _order.value
        }
        _items.value = _items.value + (item.id to item)
        _states.value = _states.value + (item.id to DownloadState.Resolving)
        _routeSummary.value = _routeSummary.value - item.id

        val engine = DownloadEngine()
        engines[item.id] = engine
        val generation = (generations[item.id] ?: 0) + 1
        generations[item.id] = generation

        jobs[item.id] = scope.launch {
            try {
                val file = performDownload(item, client, engine, settings)
                // 默认保存到安卓系统公共目录：私有暂存完成后复制一份到
                // Download/ArtifactBoost/（10+ 走 MediaStore，无需权限；7~9 直写）。
                // 发布失败也不影响本次下载，保留私有文件兜底。
                val published = withContext(Dispatchers.IO) {
                    runCatching { PublicDownloads.publish(appContext, file) }.getOrNull()
                }
                if (published != null) {
                    val prev = _routeSummary.value[item.id]
                    val saved = "已保存到 ${published.displayPath}"
                    setRouteSummary(item.id, if (prev.isNullOrBlank()) saved else "$prev · $saved")
                }
                _states.value = _states.value + (
                    item.id to DownloadState.Finished(file, published?.uri, published?.displayPath)
                    )
            } catch (e: Exception) {
                if (isCancellation(e)) {
                    // 取消有两种：用户点了暂停（状态已经是 Paused，别覆盖），
                    // 以及用户点了取消（回 Idle）
                    if (_states.value[item.id] !is DownloadState.Paused) {
                        _states.value = _states.value + (item.id to DownloadState.Idle)
                    }
                } else {
                    _states.value = _states.value + (item.id to DownloadState.Failed(e.message ?: "下载失败"))
                }
            } finally {
                // 只有「还是这一代任务」才清理；否则会把用户刚点「继续」新建的那一代误删
                if (generations[item.id] == generation) {
                    generations.remove(item.id)
                    engines.remove(item.id)
                    jobs.remove(item.id)
                }
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
    }

    fun cancel(item: DownloadItem) {
        engines[item.id]?.cancel()
        jobs[item.id]?.cancel()
        jobs.remove(item.id)
        engines.remove(item.id)
        // 用户主动取消：连同半成品一起清掉，不恢复
        ResumeStore.discard(item.id)
        _lastProgress.value = _lastProgress.value - item.id
        _states.value = _states.value + (item.id to DownloadState.Idle)
    }

    fun remove(item: DownloadItem) {
        engines[item.id]?.cancel()
        jobs[item.id]?.cancel()
        engines.remove(item.id)
        jobs.remove(item.id)
        ResumeStore.discard(item.id)
        _lastProgress.value = _lastProgress.value - item.id
        _states.value = _states.value - item.id
        _routeSummary.value = _routeSummary.value - item.id
        _items.value = _items.value - item.id
        _order.value = _order.value - item.id
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
    ): File {
        val request = makeRequest(item, client, settings)
        var lastError: Throwable = DownloadException.BadResponse
        for (attempt in 0 until 2) {
            try {
                if (attempt > 0) {
                    setRouteSummary(item.id, "网络抖动，正在重试…")
                    delay(RETRY_DELAY_MS)
                }
                val result = engine.download(request, outputDir) { progress ->
                    _lastProgress.value = _lastProgress.value + (item.id to progress)
                    _states.value = _states.value + (item.id to DownloadState.Downloading(progress))
                }
                setRouteSummary(item.id, "${describe(request.routes)} · 平均 ${formatSpeed(result.averageSpeed)}")
                _lastProgress.value = _lastProgress.value - item.id
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
