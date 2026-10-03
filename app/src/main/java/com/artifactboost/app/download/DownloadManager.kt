package com.artifactboost.app.download

import android.content.Context
import com.artifactboost.app.data.AccelerationSettings
import com.artifactboost.app.data.DownloadItem
import com.artifactboost.app.data.DownloadRoute
import com.artifactboost.app.data.DownloadSource
import com.artifactboost.app.data.GitHubClient
import com.artifactboost.app.data.GitHubException
import com.artifactboost.app.data.RouteProbe
import com.artifactboost.app.data.RouteScope
import com.artifactboost.app.data.ScoredRoute
import com.artifactboost.app.data.SessionManager
import com.artifactboost.app.util.formatSpeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** 测速用的真实目标（优先产物，其次构建日志） */
data class SpeedTestTarget(
    val url: String,
    val label: String,
    val isPrivate: Boolean,
    /** 已知体积：测速时从文件中部取样，避开 TCP 慢启动 */
    val size: Long? = null,
)

/** 单个下载任务的状态 */
sealed class DownloadState {
    data object Idle : DownloadState()
    data object Resolving : DownloadState()
    data class Downloading(val progress: DownloadProgress) : DownloadState()
    data class Finished(val file: File) : DownloadState()
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

    private val engines = mutableMapOf<String, DownloadEngine>()
    private val jobs = mutableMapOf<String, Job>()

    /** 已下载文件的落盘目录：外部私有目录/Artifacts，可从「文件」App 访问 */
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

        jobs[item.id] = scope.launch {
            try {
                val file = performDownload(item, client, engine, settings)
                _states.value = _states.value + (item.id to DownloadState.Finished(file))
            } catch (e: Exception) {
                if (isCancellation(e)) {
                    _states.value = _states.value + (item.id to DownloadState.Idle)
                } else {
                    _states.value = _states.value + (item.id to DownloadState.Failed(e.message ?: "下载失败"))
                }
            } finally {
                engines.remove(item.id)
                jobs.remove(item.id)
            }
        }
    }

    fun cancel(item: DownloadItem) {
        engines[item.id]?.cancel()
        jobs[item.id]?.cancel()
        jobs.remove(item.id)
        engines.remove(item.id)
        _states.value = _states.value + (item.id to DownloadState.Idle)
    }

    fun remove(item: DownloadItem) {
        engines[item.id]?.cancel()
        jobs[item.id]?.cancel()
        engines.remove(item.id)
        jobs.remove(item.id)
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

    /** 设置页测速用：在用户自己的仓库里找一个真实的下载目标 */
    suspend fun findTestTarget(): SpeedTestTarget? =
        withTimeoutOrNull(FIND_TARGET_TIMEOUT_MS) { findTestTargetUnsafe() }

    /**
     * 找测速目标限时：内部是串行网络请求（仓库→构建→产物→签名地址），
     * 弱网下单个请求就可能卡 20~30s，整体不限时会让设置页转圈一分钟以上。
     * 超时直接返回 null，调用方按“无可用目标”提示。
     */
    private companion object {
        const val FIND_TARGET_TIMEOUT_MS = 20_000L
    }

    private suspend fun findTestTargetUnsafe(): SpeedTestTarget? {
        val client = session.client.value ?: return null
        val repos = try {
            client.repos(page = 1)
        } catch (_: Exception) {
            return null
        }
        // 公开仓库优先：私有仓库的签名地址不应该交给镜像去测速
        val ordered = repos.sortedWith(
            compareBy({ if (it.isPrivate) 1 else 0 }, { it.name }),
        )
        for (repo in ordered.take(5)) {
            val runs = try {
                client.workflowRuns(repo)
            } catch (_: Exception) {
                continue
            }
            val run = runs.firstOrNull() ?: continue

            val candidate = try {
                client.artifacts(repo, run)
                    .filter { !it.expired }
                    .maxByOrNull { it.sizeInBytes }
            } catch (_: Exception) {
                null
            }
            if (candidate != null) {
                try {
                    val url = client.resolveDownloadUrl(
                        DownloadSource.Artifact(repo.fullName, candidate.id),
                    )
                    return SpeedTestTarget(url, "${repo.name} · ${candidate.name}", repo.isPrivate, candidate.sizeInBytes)
                } catch (_: Exception) {
                    // 换日志再试
                }
            }
            try {
                val url = client.resolveDownloadUrl(DownloadSource.RunLogs(repo.fullName, run.id))
                return SpeedTestTarget(url, "${repo.name} · 构建日志", repo.isPrivate)
            } catch (_: Exception) {
                // 换下一个仓库
            }
        }
        return null
    }

    // MARK: - 下载主流程

    /**
     * 解析签名地址 → 选通道 → 下载。
     * 签名地址有时效，整体失败后重新解析再试一次。
     */
    private suspend fun performDownload(
        item: DownloadItem,
        client: GitHubClient,
        engine: DownloadEngine,
        settings: AccelerationSettings,
    ): File {
        var lastError: Throwable = DownloadException.BadResponse
        for (attempt in 0 until 2) {
            try {
                val signed = client.resolveDownloadUrl(item.source)
                return runDownload(item, engine, signed, settings)
            } catch (e: Exception) {
                if (!shouldRetry(e)) throw e
                lastError = e
                if (attempt == 0) {
                    _states.value = _states.value + (item.id to DownloadState.Resolving)
                }
            }
        }
        throw lastError
    }

    private suspend fun runDownload(
        item: DownloadItem,
        engine: DownloadEngine,
        signedUrl: String,
        settings: AccelerationSettings,
    ): File {
        // ghfast 这类镜像只认 github.com 原始地址，套签名地址会被拒，
        // 所以「这条通道该套哪个 URL」必须逐条算，不能统一用 signedUrl。
        val githubUrl = item.source.ghfastEligibleUrl

        var plan: List<ScoredRoute>
        var note: String

        val saved = settings.savedPlan(item.isPrivate, githubUrl)
        if (saved != null) {
            // 设置页已经测过速：直接用保存的最快通道
            plan = saved
            note = "${saved[0].route.name}（设置页测速 ${formatSpeed(saved[0].speed)}）"
        } else {
            val candidates = settings.candidateRoutes(item.isPrivate, githubUrl)
            if (candidates.size <= 1) {
                plan = listOf(ScoredRoute(candidates[0], 1.0))
                note = if (item.isPrivate && settings.mode == com.artifactboost.app.data.RouteMode.SMART) {
                    "直连（私有仓库不走镜像）"
                } else {
                    candidates[0].name
                }
            } else {
                setRouteSummary(item.id, "正在测速选通道…")
                val measured = RouteProbe.measureAll(
                    candidates,
                    signedUrl = signedUrl,
                    githubUrl = githubUrl,
                    knownSize = item.size,
                )
                val fastest = measured.firstOrNull()?.speed ?: 0.0
                val viable = measured.filter { it.speed >= fastest * 0.4 }
                if (viable.isEmpty()) {
                    plan = listOf(ScoredRoute(DownloadRoute.DIRECT, 1.0))
                    note = "直连（测速失败）"
                } else {
                    plan = viable
                    note = describe(plan) + "（实测 ${formatSpeed(fastest)}）"
                    if (settings.mode == com.artifactboost.app.data.RouteMode.SMART) {
                        // 顺手把结果存下来，下次下载和设置页都能直接复用
                        val best = measured.first()
                        settings.record(best.route, best.speed).save(appContext)
                    }
                }
            }
        }

        val connections = settings.clampedConnections
        val outputDir = outputDir
        // 源码包由 GitHub 现场打包，通常不支持 Range；但引擎会自己探测，
        // 真拿到 206 就自动升级成多线程，所以这里只是「别抱太大期望」的提示
        val mayChunk = item.source.supportsChunkedDownload

        // 逐条通道算出它该用的 URL：ghfast 用 github.com 地址，其余用签名地址
        val routeUrls = resolveRouteUrls(plan, signedUrl, githubUrl)

        return try {
            val result = engine.download(
                routeUrls = routeUrls,
                routes = plan,
                fileName = item.fileName,
                connections = connections,
                outputDir = outputDir,
                allowChunking = mayChunk,
            ) { progress ->
                _states.value = _states.value + (item.id to DownloadState.Downloading(progress))
            }
            setRouteSummary(item.id, "$note · 平均 ${formatSpeed(result.averageSpeed)}")
            result.file
        } catch (e: Exception) {
            // 通道可能失效/被限流，整体回退直连再试一次
            if (!shouldRetry(e) || plan.none { !it.route.isDirect }) throw e
            val result = engine.download(
                routeUrls = listOf(DownloadRoute.DIRECT to signedUrl),
                routes = listOf(ScoredRoute(DownloadRoute.DIRECT, 1.0)),
                fileName = item.fileName,
                connections = connections,
                outputDir = outputDir,
                allowChunking = mayChunk,
            ) { progress ->
                _states.value = _states.value + (item.id to DownloadState.Downloading(progress))
            }
            setRouteSummary(item.id, "直连（$note 失败已回退） · 平均 ${formatSpeed(result.averageSpeed)}")
            result.file
        }
    }

    /**
     * 给每条通道算出实际请求的 URL。
     *
     * - [RouteScope.GITHUB_ONLY]（ghfast）：套 `https://github.com/...` 稳定地址；
     *   若这次下载没有稳定地址，就把这条通道剔掉（避免送上去必然 400）。
     * - 其余通道：套已签名的真实地址（原来的行为）。
     */
    private fun resolveRouteUrls(
        plan: List<ScoredRoute>,
        signedUrl: String,
        githubUrl: String?,
    ): List<Pair<DownloadRoute, String>> {
        val urls = plan.mapNotNull { scored ->
            when (scored.route.scope) {
                RouteScope.ANY -> scored.route to scored.route.apply(signedUrl)
                RouteScope.GITHUB_ONLY -> {
                    val base = githubUrl ?: return@mapNotNull null
                    scored.route to scored.route.apply(base)
                }
            }
        }
        // 全被剔掉（理论上不会，因为直连永远是 ANY）时至少保底直连
        return urls.ifEmpty { listOf(DownloadRoute.DIRECT to signedUrl) }
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
