package com.artifactboost.app.download

import com.artifactboost.app.data.DownloadRoute
import com.artifactboost.app.data.GitHubClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** 一条通道的实测探测结果 */
internal data class RouteProbeResult(
    val route: DownloadRoute,
    /** 从发出请求到拿到响应的耗时（纳秒） */
    val elapsedNanos: Long,
    /** 探测拿到的首块速度（字节/秒），探测失败为 0 */
    val speed: Double,
    /** 是否可用（能连通、且没有立刻被拒） */
    val ok: Boolean,
    /** 服务端回的 HTTP 状态码（诊断用） */
    val status: Int?,
)

/**
 * 通道并发探测 + 结果缓存：解决「解析下载地址有点慢」里的后半段 ——
 * 以前是「按列表顺序挑通道」，4 个镜像里前 3 个是死的就得串行超时三次；
 * 现在同时打所有通道，谁先给出可用响应谁上岗，死的立刻剔除。
 *
 * 结果按「主机名」缓存 5 分钟：同一个域名下次直接复用排序，
 * 不再重复探测，第二次打开同一个仓库基本是瞬发。
 */
internal object RouteProbeCache {

    /** 缓存有效期：镜像的可用性变化很快，5 分钟是个折中 */
    private const val TTL_MS = 300_000L

    /** 探测超时（秒）：探测本身必须快，慢了就等于把「解析慢」原样搬过来 */
    private const val TIMEOUT_SECONDS = 2L

    /** 探测时只拉一小段，够判断连通性和粗测速度就行 */
    private const val PROBE_BYTES = 32 * 1024

    private data class Entry(val probes: List<RouteProbeResult>, val at: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    /** 探测专用客户端：短超时、不复用连接池（避免污染下载用的连接） */
    private val probeClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    fun cached(host: String): List<RouteProbeResult>? {
        val entry = entries[host] ?: return null
        if (System.currentTimeMillis() - entry.at >= TTL_MS) {
            entries.remove(host)
            return null
        }
        return entry.probes
    }

    fun store(probes: List<RouteProbeResult>, host: String) {
        if (probes.isEmpty()) return
        entries[host] = Entry(probes, System.currentTimeMillis())
    }

    /** 清空缓存（用户切换下载源时调用：换源后旧的排序不再适用） */
    fun invalidateAll() {
        entries.clear()
    }

    /**
     * 并发探测所有通道。
     *
     * 每条通道都发一个 `Range: bytes=0-<PROBE_BYTES>` 的小请求，
     * 立刻量出「响应延迟」和「这一小段的速度」。
     * 全部并行，总耗时约等于最快那条的耗时（≈ RTT），而不是最慢那条。
     *
     * @param routes 与 [routeUrls] 一一对应的通道
     * @return 按「可用优先 → 速度快优先 → 延迟低优先」排好序的探测结果
     */
    suspend fun probe(routes: List<DownloadRoute>, routeUrls: List<String>): List<RouteProbeResult> {
        if (routes.size != routeUrls.size || routes.isEmpty()) return emptyList()

        val results = coroutineScope {
            routes.mapIndexed { index, route ->
                async(Dispatchers.IO) { probeOne(route, routeUrls[index]) }
            }.awaitAll()
        }

        return results.sortedWith(
            compareByDescending<RouteProbeResult> { it.ok }
                .thenByDescending { it.speed }
                .thenBy { it.elapsedNanos },
        )
    }

    /** 单条通道探测。任何失败都返回 `ok = false`，绝不抛错 —— 探测不该影响主流程。 */
    private suspend fun probeOne(route: DownloadRoute, url: String): RouteProbeResult =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("Range", "bytes=0-${PROBE_BYTES - 1}")
                .header("User-Agent", GitHubClient.USER_AGENT)
                .build()

            val startedAt = System.nanoTime()
            try {
                probeClient.newCall(request).execute().use { response ->
                    val elapsed = System.nanoTime() - startedAt
                    val bytes = response.body?.bytes()?.size?.toLong() ?: 0L
                    // 429/503 也算「活着但被限流」：标记不可用，让别的通道先上
                    val usable = (response.code == 206 || response.code == 200) && bytes > 0
                    val seconds = maxOf(elapsed / 1_000_000_000.0, 0.001)
                    RouteProbeResult(
                        route = route,
                        elapsedNanos = elapsed,
                        speed = if (usable) bytes / seconds else 0.0,
                        ok = usable,
                        status = response.code,
                    )
                }
            } catch (_: Exception) {
                RouteProbeResult(
                    route = route,
                    elapsedNanos = System.nanoTime() - startedAt,
                    speed = 0.0,
                    ok = false,
                    status = null,
                )
            }
        }
}
