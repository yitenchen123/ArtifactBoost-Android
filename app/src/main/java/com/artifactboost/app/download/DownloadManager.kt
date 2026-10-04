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

    private val engines = mutableMapOf<String, DownloadEngine>()
    private val jobs = mutableMapOf<String, Job>()

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

    private companion object {

        /**
         * 解析签名地址单次限时：该阶段永远直连 api.github.com，
         * OkHttp 侧已有 30s callTimeout，这里是防线程池排队等意外情况的第二道保险。
         */
        const val RESOLVE_TIMEOUT_MS = 30_000L

        /** 解析重试前退避：抖动网络下立刻重打大概率再撞上，歇 1.5s 再试更划算 */
        const val RESOLVE_RETRY_DELAY_MS = 1_500L
    }

    // MARK: - 下载主流程

    /**
     * 解析签名地址 → 选通道 → 下载。
     * 签名地址有时效，整体失败后重新解析再试一次。
     *
     * 解析阶段永远直连 api.github.com（通道只影响下载阶段），弱网下单次可达 30s；
     * 这里做了三件事避免“一直转”：单次 30s 限时、重试前退避 1.5s、重试时刷出明确文案。
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
                if (attempt > 0) {
                    // 让用户看出来是在重试，而不是卡死；文案见 DownloadItemRow 的 Resolving 分支
                    setRouteSummary(item.id, "正在解析下载地址（重试 $attempt/1）…")
                    delay(RESOLVE_RETRY_DELAY_MS)
                }
                val signed = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
                    client.resolveDownloadUrl(item.source)
                }
                if (signed == null) {
                    // withTimeoutOrNull 在协程被取消时同样返回 null：先把取消抛出去，
                    // 否则会被包装成“超时”再白跑一次重试。
                    coroutineContext.ensureActive()
                    throw java.net.SocketTimeoutException(
                        "解析下载地址超时（30s）：直连 api.github.com 太慢，请检查网络后重试",
                    )
                }
                // 解析成功：清掉可能存在的“重试…”文案，后面下载会刷自己的说明
                _routeSummary.value = _routeSummary.value - item.id
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

        // 无测速：候选通道直接全部并行，初始权重均等，
        // 引擎下载中按实时吞吐动态调整分配。
        val candidates = settings.candidateRoutes(item.isPrivate, githubUrl)
        val plan: List<ScoredRoute> = candidates.map { ScoredRoute(it, 1.0) }
        val note: String = if (item.isPrivate && settings.mode == RouteMode.SMART) {
            "直连（私有仓库不走镜像）"
        } else {
            describe(plan)
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
