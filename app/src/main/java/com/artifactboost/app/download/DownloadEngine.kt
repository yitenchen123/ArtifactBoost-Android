package com.artifactboost.app.download

import com.artifactboost.app.data.GitHubClient
import com.artifactboost.app.data.ScoredRoute
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** 分片索引就是它在文件里的起始偏移，切分时不需要重编号 */
private fun Chunk(at: Long, to: Long) = Chunk(0, at, to)

/** 下载进度快照，节流后推给 UI */
data class DownloadProgress(
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val fraction: Float = 0f,
    val speedBytesPerSecond: Double = 0.0,
    /** 分段的实时明细，用于「详细信息」面板；未开始分段时为 null */
    val diagnostics: DownloadDiagnostics? = null,
)

/** 单个分段的连接状态 */
enum class SegmentState {
    PENDING,
    DOWNLOADING,
    RETRYING,
    DONE,
    FAILED;

    /** 展示用文案（「详细信息」面板里直接显示） */
    val label: String
        get() = when (this) {
            PENDING -> "等待中"
            DOWNLOADING -> "下载中"
            RETRYING -> "重试中"
            DONE -> "已完成"
            FAILED -> "失败"
        }
}

/**
 * 一条「车道」的实时快照 —— 也就是一个 worker 当前正在啃的区间。
 *
 * 这是「详细信息」面板的数据源：用户在界面上一眼能看到哪条连接在跑、
 * 跑到哪个区间、当前多快、有没有在重试、服务端回了什么状态码。
 */
data class LaneSnapshot(
    val laneId: Int,
    val routeName: String,
    val url: String,
    val start: Long,
    val end: Long,
    val downloaded: Long,
    val speedBytesPerSecond: Double,
    val state: SegmentState,
    val attempt: Int,
    val lastStatus: Int?,
) {
    val length: Long get() = end - start + 1
    val fraction: Float
        get() = if (length > 0) (downloaded.toFloat() / length).coerceIn(0f, 1f) else 0f
}

/** 某条通道的实测速度与占用情况 */
data class RouteStats(
    val name: String,
    val speedBytesPerSecond: Double,
    val isActive: Boolean,
)

/** 整次下载的诊断快照 */
data class DownloadDiagnostics(
    val lanes: List<LaneSnapshot>,
    val targetLanes: Int,
    /** 已经完成的切片数 / 累计切出的切片总数 */
    val doneSlices: Int,
    val totalSlices: Int,
    val retries: Int,
    val throttles: Int,
    val splits: Int,
    val routes: List<RouteStats>,
    /** 当前正在用的下载地址（可复制） */
    val activeUrl: String,
)

sealed class DownloadException(message: String) : IOException(message) {
    object BadResponse : DownloadException("下载失败：服务器响应异常")
    object Cancelled : DownloadException("下载已取消")
    object Incomplete : DownloadException("下载失败：数据校验不通过（可能断流），请重试")

    /** 服务器忽略 Range 头（回 200 全量）：这条地址不能用于分段下载 */
    object NoRange : DownloadException("下载失败：该通道不支持分段下载")

    /** 服务器明确要求我们慢一点（429 / 503 等），需要按 Retry-After 退避 */
    class Throttled(val code: Int, val retryAfterMillis: Long?) :
        DownloadException("下载失败：服务器限流（$code）")
}

data class DownloadResult(
    val file: File,
    val averageSpeed: Double,
    /** 本次实际吃满的并发数，用于界面说明 */
    val lanes: Int = 1,
)

/** 一个待下载的区间（左闭右闭） */
internal data class Chunk(
    val index: Int,
    val start: Long,
    val end: Long,
) {
    val length: Long get() = end - start + 1
}

/**
 * 一个通道：一个独立的 OkHttpClient（独立连接池）+ 地址 + 实时吞吐。
 *
 * `endpoints` 不可变：`[0]` 是主地址，`last` 是兜底（直连）。
 * 旧的 `endpoints = drop(1)+first` 会全局变异共享对象，
 * 并发重试时 A 切到直连、B 又切回去，兜底形同虚设。
 * 现在重试只用局部下标选地址，不再变异共享状态。
 */
private class RouteChannel(
    val client: OkHttpClient,
    val endpoints: List<String>,
    val speedHint: Double,
    /** 展示名（直连 / gh-proxy.com / …），用于诊断面板 */
    val name: String,
) {
    @Volatile var measuredSpeed: Double = speedHint
    /** 是否本通道处于「被限流降额」状态 */
    @Volatile var throttled: Boolean = false

    val primary: String get() = endpoints.first()
    val fallback: String get() = endpoints.last()
}

/**
 * 正在飞的请求登记簿。
 *
 * `call.cancel()` 是唯一能让阻塞中的 `execute()` 立刻抛 IOException 的手段；
 * 光靠协程取消是不够的 —— 阻塞在 socket read 上的线程不会理会协程状态。
 * 取消时把这里所有 Call 一起 cancel，所有线程才会「同时」退出，
 * 而不是各自等到下一次读超时（120s）才反应过来。
 */
private class CallRegistry {
    private val live = ConcurrentLinkedDeque<okhttp3.Call>()

    fun register(call: okhttp3.Call) {
        live.add(call)
    }

    fun release(call: okhttp3.Call) {
        live.remove(call)
    }

    /** 取消所有在飞的请求 */
    fun cancelAll() {
        live.forEach { runCatching { it.cancel() } }
        live.clear()
    }
}

/**
 * 车道状态看板：所有 worker 把自己的实时状态登记在这里，
 * 由进度回调按 250ms 的既有节流节奏取走 —— 不额外起轮询、不加网络开销。
 *
 * 同时兼任「通道级并发配额」的计数：命中 429/503 的通道会被临时降额，
 * 免得在同一根被限流的线路上继续加压、越限越死。
 */
private class LaneBoard(private val target: Int) {

    private val lanes = java.util.concurrent.ConcurrentHashMap<Int, LaneSnapshot>()
    private val routeNames = java.util.concurrent.ConcurrentHashMap<Int, String>()

    /** 每条通道当前的惩罚计数：>0 表示被限流降额中 */
    private val penalties = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()

    val doneSlices = AtomicInteger(0)
    val totalSlices = AtomicInteger(0)
    val retries = AtomicInteger(0)
    val throttles = AtomicInteger(0)
    val splits = AtomicInteger(0)

    @Volatile var activeUrl: String = ""

    fun update(snapshot: LaneSnapshot) {
        lanes[snapshot.laneId] = snapshot
    }

    fun remove(laneId: Int) {
        lanes.remove(laneId)
        routeNames.remove(laneId)
    }

    /** 只取在跑的车道（供「哪条通道正在干活」判断用） */
    fun lanesSnapshot(): List<LaneSnapshot> = lanes.values.toList()

    fun bindRoute(laneId: Int, routeName: String) {
        routeNames[laneId] = routeName
    }

    /** 通道被限流：记一笔惩罚，稍后自动衰减 */
    fun penalize(routeName: String) {
        penalties.getOrPut(routeName) { AtomicInteger(0) }.incrementAndGet()
    }

    /** 通道跑得顺：把惩罚往回减 */
    fun reward(routeName: String) {
        val counter = penalties[routeName] ?: return
        if (counter.get() > 0) counter.decrementAndGet()
    }

    fun penaltyOf(routeName: String): Int = penalties[routeName]?.get() ?: 0

    fun snapshot(routes: List<RouteStats>): DownloadDiagnostics = DownloadDiagnostics(
        lanes = lanes.values.sortedBy { it.start },
        targetLanes = target,
        doneSlices = doneSlices.get(),
        totalSlices = totalSlices.get(),
        retries = retries.get(),
        throttles = throttles.get(),
        splits = splits.get(),
        routes = routes,
        activeUrl = activeUrl,
    )
}

/**
 * 多线程分段下载引擎（滑动窗口 + 分片续做）。
 *
 * 产物实际托管在 Azure Blob Storage，支持 Range 请求；单连接被限速时，
 * 多并发能显著提升总速度——这正是本引擎存在的意义。
 *
 * 与「一次性切块 + 固定分配给各连接」的老做法相比，这里的调度是自适应的：
 *
 *  1. **持久 worker 池**：并发跑满 `connections` 个任务，每个任务只取「一个小分片」；
 *     而不是开 N 个协程去啃又大又不均匀的一大块。
 *  2. **分片续做（steal）**：任何时刻若只剩少量区间在跑、而空闲 worker 还很多，
 *     就把末尾那段区间再砍一半。这样下载最后阶段不再是「一个慢连接收尾、
 *     其它连接全部闲着」，而是所有连接一起把剩下的数据吃完。
 *  3. **动态分片大小**：起步小、快的时候逐步放大（减少请求数），
 *     带宽掉下来时又迅速缩小（缩短每次重试的代价）。
 *  4. **索引即偏移**：分片编号就是它在文件里的字节偏移，所以续做/分片不需要重编号，
 *     写盘也可以乱序并发。
 *  5. **指数退避 + Retry-After**：命中 Azure 的 503 ServerBusy / 429 时限速时按官方
 *     建议退避，而不是火上浇油地硬重试，否则会被越限越死。
 *  6. **渐进建连 + 实时吞吐反馈**：避免「一上来几百个请求把服务端打限流」和
 *     「某个通道早就慢下来了却还一直按旧速度分活」。
 *  7. **强制 HTTP/1.1**：Cloudflare 这类 CDN 会协商 HTTP/2，把所有请求多路复用到
 *     **同一条 TCP 连接**上 —— 长链路下单连接带宽就是天花板，开再多「车道」也没用。
 *  8. **通道级并发配额**：某条通道命中 429/503 就临时降它的并发，
 *     而不是继续往那根已经饱和的线路上加压。
 */
class DownloadEngine {

    @Volatile
    private var cancelled = false

    private val clients = mutableListOf<OkHttpClient>()
    private val allClients = ConcurrentLinkedDeque<OkHttpClient>()

    /** 在飞的请求：取消时要一起掐掉，否则线程会卡在 socket read 上很久 */
    private val inflight = CallRegistry()

    /**
     * 取消下载。
     *
     * 必须做到「点了就停」：置标志位 → 取消所有在飞 Call → 取消所有分发器。
     * 只置标志位是不够的，阻塞中的 `execute()` 只认 `Call.cancel()`。
     */
    fun cancel() {
        cancelled = true
        DownloadEngineFlag.cancelled = true
        inflight.cancelAll()
        allClients.forEach { it.dispatcher.cancelAll() }
    }

    /**
     * 多通道并行下载。
     *
     * @param routeUrls 每条通道 **各自** 要请求的地址（顺序与 [routes] 一一对应）。
     *        之所以逐条传进来而不是统一套一个签名地址：ghfast 这类镜像只认
     *        `github.com` 原始地址，套签名地址会被拒。
     * @param allowChunking 目标是否可能支持分段；为 false 时先走单连接，但在读到 206
     *        之后依旧会自动升级为分段下载（源码包也有 206 的时候）。
     */
    suspend fun download(
        routeUrls: List<Pair<com.artifactboost.app.data.DownloadRoute, String>>,
        routes: List<ScoredRoute>,
        fileName: String,
        connections: Int,
        outputDir: File,
        allowChunking: Boolean = true,
        progress: (DownloadProgress) -> Unit,
    ): DownloadResult = withContext(Dispatchers.IO) {
        cancelled = false
        DownloadEngineFlag.cancelled = false
        val startedAt = System.nanoTime()
        val plan = routes.ifEmpty { listOf(ScoredRoute(com.artifactboost.app.data.DownloadRoute.DIRECT, 1.0)) }
        val urls = routeUrls.map { it.second }.ifEmpty {
            listOf(com.artifactboost.app.data.DownloadRoute.DIRECT.apply(""))
        }

        val tempDir = File(outputDir.parentFile ?: outputDir, "tmp-${System.currentTimeMillis()}")
        if (!tempDir.exists() && !tempDir.mkdirs()) throw DownloadException.BadResponse
        outputDir.mkdirs()

        var outFile = File(outputDir, fileName)
        if (outFile.exists()) {
            outFile = File(outputDir, "${System.currentTimeMillis().toString().takeLast(6)}-$fileName")
        }

        try {
            // 先探测体积 + 确认服务器是否真的支持 Range
            val probe = if (allowChunking) probeSize(urls) else null
            val total = probe?.total
            val lanes = connections.coerceIn(1, MAX_LANES)

            if (total == null || total <= 0) {
                // 探测不到体积（不少接口不回 Content-Length）：
                // 先单连接跑，只要响应是 206 就现场升级成多线程分段
                val single = downloadSingle(urls.first(), outFile, lanes, progress)
                val elapsed = maxOf((System.nanoTime() - startedAt) / 1_000_000_000.0, 0.05)
                return@withContext DownloadResult(single.file, single.bytes / elapsed, single.lanes)
            }

            if (!probe.chunked || total < MIN_CHUNKED_TOTAL) {
                // 服务器忽略了 Range（返回 200 全量），或者文件太小不值得分段
                val single = downloadSingle(urls.first(), outFile, lanes, progress)
                val elapsed = maxOf((System.nanoTime() - startedAt) / 1_000_000_000.0, 0.05)
                return@withContext DownloadResult(single.file, single.bytes / elapsed, single.lanes)
            }

            val result = segmentDownload(urls, total, outFile, tempDir, lanes, plan, progress)
            val elapsed = maxOf((System.nanoTime() - startedAt) / 1_000_000_000.0, 0.05)
            DownloadResult(result, total / elapsed, lanes)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // MARK: - 分段下载主循环

    /**
     * 滑动窗口 + 分片续做（work stealing）的调度器。
     *
     * `lanes` 是目标并发数，同时也是「同时在跑的区间数」上限。
     * 每个区间按 [sliceTarget] 的粒度取数据，写完一片就接着取下一片；
     * 一旦池子里没活儿而还有连接闲着，就从末尾区间切一刀 —— 空闲连接立刻有活干。
     *
     * 这样就不会再出现「刚开始很快、到后面掉到几十 KB」：
     * 那正是老实现里「一条慢连接独自收尾，其余连接全部空转」造成的。
     */
    private suspend fun segmentDownload(
        urls: List<String>,
        total: Long,
        outFile: File,
        tempDir: File,
        lanes: Int,
        plan: List<ScoredRoute>,
        progress: (DownloadProgress) -> Unit,
    ): File {
        // 车道状态看板：worker 实时登记，进度回调每拍取走一份快照。
        // 注意它必须早于 RouteChannel 建好 —— 下面算通道配额时要读它的惩罚计数。
        val board = LaneBoard(lanes)
        // 通道列表在下面才建好，这里先用空引用占位，建好后立刻回填。
        var channelsRef: List<RouteChannel> = emptyList()
        val accumulator = ProgressAccumulator(total, progress) {
            // 诊断快照走的是「现取」而不是「定时轮询」：只有真要推进度的那一拍才组数据，
            // 零额外开销。routes() 里带上各通道实测速度与是否在跑。
            val active = board.lanesSnapshot().map { it.routeName }.toSet()
            board.snapshot(
                channelsRef.map {
                    RouteStats(
                        name = it.name,
                        speedBytesPerSecond = it.measuredSpeed,
                        isActive = it.name in active,
                    )
                },
            )
        }

        // 关键：Cloudflare 这类 CDN 会协商 HTTP/2，所有请求被多路复用到同一条 TCP 连接上，
        // 长链路下单连接带宽就是天花板，开再多「车道」也没用。
        // 因此这里显式只允许 HTTP/1.1，让每条车道各自占一条 TCP —— 真正并行。
        // 同时拆成多个 OkHttpClient（各自独立连接池），进一步保证连接不复用。
        val sessionCount = minOf(8, maxOf(1, lanes / 8))
        val perSessionLimit = maxOf(1, lanes / sessionCount)
        // 调度器的排队上限要比实际并发宽一些：某条连接卡住时，
        // 后面的请求不至于被它的配额堵在门外。
        val queueLimit = maxOf(perSessionLimit, (perSessionLimit * 3) / 2)

        val sessionClients = (0 until sessionCount).map {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .callTimeout(0, TimeUnit.MILLISECONDS) // 不设总时长上限：大文件要慢慢下
                .followRedirects(true)
                // 只走 HTTP/1.1：避免 h2 把所有请求挤进一条 TCP
                .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
                .retryOnConnectionFailure(true)
                .connectionPool(okhttp3.ConnectionPool(maxOf(perSessionLimit, 8), 5, TimeUnit.MINUTES))
                .dispatcher(okhttp3.Dispatcher().apply {
                    maxRequests = queueLimit
                    maxRequestsPerHost = queueLimit
                })
                .build()
        }
        synchronized(clients) { clients.addAll(sessionClients) }
        allClients.addAll(sessionClients)

        // 关键：Dispatchers.IO 默认最多 64 条线程。lanes 超过 64 时，多出来的
        // worker 会一直排在 IO 池队列里等线程 —— 「选了 512 却只跑 64」的元凶就是它。
        // 所以给本次下载建一个专属固定线程池：runSlice 里全是阻塞 IO
        // （execute() + 写盘），每条车道独占一条线程最直接，下载结束整体回收。
        // （声明在 try 外面：finally 里要 close 它。）
        val enginePool = Executors.newFixedThreadPool(lanes).asCoroutineDispatcher()

        try {
            val direct = urls.first()
            val channels = plan.mapIndexed { index, scored ->
                val primary = urls[minOf(index, urls.size - 1)]
                // 镜像挂掉/被限流时自动退回直连
                val endpoints = if (primary == direct) listOf(primary) else listOf(primary, direct)
                RouteChannel(
                    client = sessionClients[index % sessionClients.size],
                    endpoints = endpoints,
                    speedHint = maxOf(scored.speed, 1.0),
                    name = scored.route.name,
                )
            }
            channelsRef = channels

            val output = RandomAccessFile(outFile, "rw")
            output.setLength(total)

            // 预切分 lanes*4：首轮就能派满并发，避免“池子只有 1 个区间→只能派出 1 条”的调度饿死（与 iOS 同步）。
            val pool = SlicePool(total, lanes * 4)
            val written = AtomicLong(0)
            val startedAt = System.nanoTime()

            // 车道编号只增不减：worker 收工后编号不复用，
            // 这样诊断面板上「车道 #7 干了什么」不会因为复用而张冠李戴。
            val laneCounter = AtomicInteger(0)

            // 每条通道分到的并发额度（单通道配额）：通道数少就给得多，
            // 免得 4 条镜像时每条只剩 4 个并发、根本压不满带宽。
            val perChannelQuota = maxOf(1, lanes / maxOf(channels.size, 1))

            try {
                coroutineScope {
                    val jobs = mutableListOf<Job>()
                    var roundRobin = 0

                    // 渐进建连：不再一上来就把 lanes 顶满。
                    // 起步瞬间几百个请求同时砸过去，Azure/Cloudflare 会直接回 503 ServerBusy，
                    // 一旦被限流就得指数退避，整段下载反而更慢。
                    // 改成每 CONNECTION_RAMP_INTERVAL_MS 放一档，跑到目标并发后再全速调度。
                    var allowedLanes = minOf(rampStepFor(lanes), lanes)
                    var lastRampAt = System.nanoTime()

                    // 无进展保护：所有 worker 都在「失败→重派→再失败」里空转、
                    // 文件一个字节都没涨，这种状态持续 90 秒就判定全线失败。
                    // 没有它，全线断网/磁盘写挂时调度器会永远空转下去。
                    var lastProgressBytes = written.get()
                    var lastProgressAt = System.nanoTime()

                    while (true) {
                        // 取消后立刻退出调度循环，不再派新活儿
                        if (cancelled || DownloadEngineFlag.cancelled) throw DownloadException.Cancelled

                        val nowProgress = written.get()
                        if (nowProgress != lastProgressBytes) {
                            lastProgressBytes = nowProgress
                            lastProgressAt = System.nanoTime()
                        } else if (jobs.isNotEmpty() &&
                            (System.nanoTime() - lastProgressAt) > 90_000_000_000L
                        ) {
                            throw DownloadException.Incomplete
                        }

                        jobs.removeAll { it.isCompleted }

                        // 0) 建连爬坡：到点就放开一档并发
                        val nowNanos = System.nanoTime()
                        if (allowedLanes < lanes &&
                            (nowNanos - lastRampAt) / 1_000_000 >= CONNECTION_RAMP_INTERVAL_MS
                        ) {
                            allowedLanes = minOf(allowedLanes + rampStepFor(lanes), lanes)
                            lastRampAt = nowNanos
                        }

                        // 1) 把并发顶到「当前允许值」；通道被限流时按配额收缩
                        var assigned = false
                        while (jobs.size < allowedLanes) {
                            // 此刻实际可用的并发额度：被限流的通道要临时降额，
                            // 免得在同一根已经饱和的线路上继续加压、越限越死。
                            val quota = channels.sumOf { channel ->
                                if (channel.throttled || board.penaltyOf(channel.name) > 0) {
                                    maxOf(1, perChannelQuota / 2)
                                } else {
                                    perChannelQuota
                                }
                            }
                            if (jobs.size >= quota) break

                            val work = nextWork(pool, jobs.size, lanes, total) ?: break
                            val channelIndex = pickChannel(channels, roundRobin)
                            val channel = channels[channelIndex]
                            roundRobin = (roundRobin + 1) % channels.size

                            val laneId = laneCounter.getAndIncrement()
                            board.bindRoute(laneId, channel.name)
                            // 先登记一条 PENDING，让面板立刻能看到「这条车道已就位」
                            board.update(
                                LaneSnapshot(
                                    laneId = laneId,
                                    routeName = channel.name,
                                    url = channel.primary,
                                    start = work.start,
                                    end = work.end,
                                    downloaded = 0,
                                    speedBytesPerSecond = 0.0,
                                    state = SegmentState.PENDING,
                                    attempt = 1,
                                    lastStatus = null,
                                ),
                            )

                            val capturedChannels = channels.toList()
                            jobs += launch(enginePool) {
                                runSlice(
                                    laneId = laneId,
                                    channel = channel,
                                    channels = capturedChannels,
                                    initial = work,
                                    pool = pool,
                                    lanes = lanes,
                                    total = total,
                                    output = output,
                                    written = written,
                                    accumulator = accumulator,
                                    inflight = inflight,
                                    board = board,
                                )
                                board.remove(laneId)
                            }
                            assigned = true
                        }

                        // 2) 全干完了
                        if (jobs.isEmpty()) break

                        // 3) 没活儿可派：等一小会儿再评估，别忙等烧 CPU
                        if (!assigned) delay(delayFor(pool, jobs.size, lanes, startedAt))
                    }

                    jobs.forEach { it.cancel() }
                }

                if (written.get() != total) throw DownloadException.Incomplete
                runCatching { output.fd.sync() }
            } finally {
                runCatching { output.close() }
            }

            accumulator.finish(total)
            return outFile
        } finally {
            enginePool.close()
            synchronized(clients) { clients.removeAll(sessionClients) }
            sessionClients.forEach {
                allClients.remove(it)
                it.dispatcher.cancelAll()
                it.connectionPool.evictAll()
            }
        }
    }

    // MARK: - 单连接下载（不支持分段 / 探测不到体积时）

    /**
     * 单连接下载。会顺手看响应头：只要拿到 206，就说明服务端支持 Range，
     * 立刻放弃单连接、改用多线程分段引擎重下 ——
     * 很多「日志包只能单线程」其实是误判。
     */
    private suspend fun downloadSingle(
        url: String,
        outFile: File,
        lanes: Int,
        progress: (DownloadProgress) -> Unit,
    ): SingleOutcome {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .build()
        synchronized(clients) { clients.add(client) }
        allClients.add(client)

        try {
            // 先带 Range 试一小段：能拿到 206 就说明可以分段
            val rangeProbe = Request.Builder()
                .url(url)
                .header("Range", "bytes=0-${SINGLE_PROBE_BYTES - 1}")
                .header("User-Agent", GitHubClient.USER_AGENT)
                .build()

            val ranged = withContext(Dispatchers.IO) {
                val call = client.newCall(rangeProbe)
                inflight.register(call)
                try {
                    call.execute().use { it.code == 206 }
                } finally {
                    inflight.release(call)
                }
            }

            currentCoroutineContext().ensureActive()
            if (DownloadEngineFlag.cancelled) throw DownloadException.Cancelled

            if (ranged) {
                val total = probeSize(listOf(url))?.total
                if (total != null && total >= MIN_CHUNKED_TOTAL) {
                    // 升级：交给分段引擎跑，用独立临时目录避免和外层冲突
                    val upgradeDir = File(outFile.parentFile ?: outFile.absoluteFile.parentFile, "u-${System.nanoTime()}")
                    upgradeDir.mkdirs()
                    try {
                        val file = segmentDownload(
                            urls = listOf(url),
                            total = total,
                            outFile = outFile,
                            tempDir = upgradeDir,
                            lanes = lanes,
                            plan = listOf(ScoredRoute(com.artifactboost.app.data.DownloadRoute.DIRECT, 1.0)),
                            progress = progress,
                        )
                        return SingleOutcome(file, total, lanes)
                    } finally {
                        upgradeDir.deleteRecursively()
                    }
                }
            }

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", GitHubClient.USER_AGENT)
                .build()

            val written = withContext(Dispatchers.IO) {
                val call = client.newCall(request)
                inflight.register(call)
                try {
                    call.execute().use { response ->
                        if (!response.isSuccessful) throw DownloadException.BadResponse
                        val body = response.body ?: throw DownloadException.BadResponse
                        val total = body.contentLength().takeIf { it > 0 } ?: 0L

                        outFile.delete()
                        var done = 0L
                        val startedAt = System.nanoTime()
                        var lastEmit = 0L

                        body.byteStream().use { input ->
                            outFile.outputStream().buffered(BUFFER_SIZE).use { sink ->
                                val buffer = ByteArray(BUFFER_SIZE)
                                while (true) {
                                    // 单连接路径同样要能秒停：标志位 + Call.cancel() 双保险
                                    if (cancelled || DownloadEngineFlag.cancelled) {
                                        throw DownloadException.Cancelled
                                    }
                                    val read = input.read(buffer)
                                    if (read <= 0) break
                                    sink.write(buffer, 0, read)
                                    done += read

                                    val now = System.currentTimeMillis()
                                    if (now - lastEmit >= PROGRESS_INTERVAL_MS) {
                                        lastEmit = now
                                        val elapsed = maxOf((System.nanoTime() - startedAt) / 1_000_000_000.0, 0.05)
                                        progress(
                                            DownloadProgress(
                                                downloadedBytes = done,
                                                totalBytes = total,
                                                fraction = if (total > 0) (done.toFloat() / total) else 0f,
                                                speedBytesPerSecond = done / elapsed,
                                            ),
                                        )
                                    }
                                }
                            }
                        }

                        progress(
                            DownloadProgress(
                                downloadedBytes = done,
                                totalBytes = maxOf(total, done),
                                fraction = 1f,
                                speedBytesPerSecond = 0.0,
                            ),
                        )
                        done
                    }
                } finally {
                    inflight.release(call)
                }
            }
            return SingleOutcome(outFile, written, 1)
        } finally {
            synchronized(clients) { clients.remove(client) }
            allClients.remove(client)
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
        }
    }

    private data class SingleOutcome(val file: File, val bytes: Long, val lanes: Int)

    // MARK: - 探测

    private data class Probe(val total: Long, val chunked: Boolean)

    /**
     * 探测文件大小：优先用 `Range: bytes=0-0`（返回 206 + Content-Range 才确认服务器支持分段），
     * 失败再退回 HEAD。逐条通道尝试，任何一条成功即可。
     */
    private suspend fun probeSize(urls: List<String>): Probe? {
        var headFallback: Long? = null
        for (url in urls) {
            val probe = probeSize(url) ?: continue
            if (probe.chunked && probe.total > 0) return probe
            if (headFallback == null && probe.total > 0) headFallback = probe.total
        }
        return headFallback?.let { Probe(it, false) }
    }

    private suspend fun probeSize(url: String): Probe? {
        val client = probeClient
        synchronized(clients) { clients.add(client) }
        allClients.add(client)

        try {
            val rangeRequest = Request.Builder()
                .url(url)
                .header("Range", "bytes=0-0")
                .header("User-Agent", GitHubClient.USER_AGENT)
                .build()
            val probed = withContext(Dispatchers.IO) {
                val call = client.newCall(rangeRequest)
                inflight.register(call)
                try {
                    call.execute().use { response ->
                        if (response.code == 206) {
                            val contentRange = response.header("Content-Range")
                            val total = contentRange?.substringAfterLast('/')?.trim()?.toLongOrNull()
                            if (total != null && total > 0) return@use Probe(total, true)
                        }
                        // 返回 200 说明服务器忽略了 Range，不能分段
                        if (response.code == 200) {
                            val length = response.header("Content-Length")?.toLongOrNull() ?: 0L
                            return@use Probe(length, false)
                        }
                        null
                    }
                } finally {
                    inflight.release(call)
                }
            }
            if (probed != null) return probed

            val headRequest = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", GitHubClient.USER_AGENT)
                .build()
            return withContext(Dispatchers.IO) {
                val call = client.newCall(headRequest)
                inflight.register(call)
                try {
                    call.execute().use { response ->
                        if (response.isSuccessful) {
                            val length = response.header("Content-Length")?.toLongOrNull()
                            if (length != null && length > 0) Probe(length, false) else null
                        } else {
                            null
                        }
                    }
                } finally {
                    inflight.release(call)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        } finally {
            synchronized(clients) { clients.remove(client) }
            allClients.remove(client)
        }
    }

    private fun isCancellation(error: Throwable): Boolean =
        cancelled || error is DownloadException.Cancelled ||
            error is CancellationException ||
            (error is IOException && error.message?.contains("Canceled") == true)

    internal companion object {
        const val BUFFER_SIZE = 1 shl 16          // 64 KB
        const val MAX_ATTEMPTS = 3
        const val PROGRESS_INTERVAL_MS = 250L

        /** 引擎并发上限（与 AccelerationSettings.MAX_CONNECTIONS 一致） */
        const val MAX_LANES = 128

        /** 单连接模式判断「能否升级为分段」时试探的字节数 */
        const val SINGLE_PROBE_BYTES = 64 * 1024

        /**
         * 渐进建连：每档放开多少条并发。
         *
         * 爬坡的目的是避开「起步瞬间几百个请求同时砸过去 → 503 ServerBusy」，
         * 但步子太小会白白浪费前几秒带宽。16 是 64 并发下的平衡点：
         * 单通道下 4 档（约 0.45s）就能顶满，既不会一开始就被限流，
         * 也不至于让用户觉得「怎么慢慢悠悠的」。
         *
         * 极限档（128/256/512）如果仍按 16/档，爬满要 4.8s，起步太肉 ——
         * 所以按 lanes/8 取步长：512 → 64/档 → 8 档 ≈ 1.2s；64 及以下仍是 16/档。
         */
        fun rampStepFor(lanes: Int): Int = maxOf(RAMP_STEP, lanes / 8)

        const val RAMP_STEP = 16

        /** 渐进建连：每隔多少毫秒放开一档（配合 RAMP_STEP 决定爬坡总时长） */
        const val CONNECTION_RAMP_INTERVAL_MS = 150L

        /** 小于这个体积不做分段：切来切去不如一条连接拉完 */
        const val MIN_CHUNKED_TOTAL = 4L * 1024 * 1024

        /** 探测专用客户端：限制总时长，防止某个通道不认 Range 时把整个文件都拉进内存 */
        val probeClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        }

        fun retryAfter(headers: Headers): Long? {
            val raw = headers.get("Retry-After")?.trim() ?: return null
            val seconds = raw.toLongOrNull() ?: return null
            return if (seconds in 0..600) seconds * 1000 else null
        }

        /** 指数退避 + 抖动；限流时优先听服务端的 Retry-After。
         *  限流退避上限压到 1.5s：worker 命中限流后要么很快回来、要么直接让位，
         *  绝不攥着区间长睡 —— 一次 Retry-After: 600 的限流不该让整条下载停十分钟。 */
        fun backoffMillis(attempt: Int, error: Exception): Long {
            if (error is DownloadException.Throttled) {
                val suggested = error.retryAfterMillis
                // 防雪崩：多个 worker 同时被限流时把退避时间错开
                val base = suggested ?: (1000L shl (attempt - 1).coerceIn(0, 4))
                val jitter = (base * 0.25 * Math.random()).toLong()
                return (base + jitter).coerceIn(250L, 1_500L)
            }
            val base = 250L shl (attempt - 1).coerceIn(0, 5)
            val jitter = (base * 0.3 * Math.random()).toLong()
            return (base + jitter).coerceAtMost(15_000L)
        }
    }
}

// MARK: - 调度原语（引擎之外，便于独立推理）

/** 一次分片请求的目标字节数下限 */
/**
 * 一次分片请求的目标字节数下限。
 *
 * 调到 64KB 是为了收尾阶段：剩余量少时，如果每片还按 128KB 取，
 * 最后那几 MB 只能被一两条连接瓜分，速度会「断崖式」掉下去。
 * 64KB 让尾段能摊给更多连接，末段也能贴着带宽跑完。
 * 再小就得不偿失 —— 请求头开销开始占比明显。
 */
internal const val MIN_SLICE_TARGET = 64 * 1024

/** 一次分片请求的目标字节数上限：太大就退化成「一条连接啃大块」了 */
internal const val MAX_SLICE_TARGET = 4 * 1024 * 1024

/**
 * 分配下一段活儿：优先拿现成的；拿不到而连接还闲着，就从末尾切一刀。
 * 这一步是「分片续做」的入口，也是收尾阶段还能保持满速的原因。
 *
 * [live] 是「当前还有多少条连接在跑」。注意它**不能传 0**：
 * `splitTail` 里 `if (live <= 0) return null`，传 0 就等于禁止切分，
 * 收尾阶段池子一空就再也派不出活儿。调用方至少应传 1（代表自己这条在跑）。
 */
internal fun nextWork(pool: SlicePool, live: Int, lanes: Int, total: Long): Chunk? {
    pool.take()?.let { return it }
    if (live >= lanes) return null
    val remaining = (total - pool.downloaded()).coerceAtLeast(0)
    return pool.splitTail(maxOf(live, 1), sliceTarget(lanes, total, remaining))
}

/**
 * 一个区间一次取多少：剩余数据越多取越大（少发请求），
 * 越接近尾声取越小（让所有连接都能分到收尾的活儿）。
 */
internal fun sliceTarget(lanes: Int, total: Long, remaining: Long): Int {
    val share = (remaining / (lanes.toLong() * 4L)).coerceAtLeast(0L) * 2L
    return share.coerceIn(MIN_SLICE_TARGET.toLong(), MAX_SLICE_TARGET.toLong()).toInt()
}

/** 没活儿可派时的等待时长：刚起步就快查快切，收尾时慢一点 */
private fun delayFor(pool: SlicePool, live: Int, lanes: Int, startedAt: Long): Long {
    val warmingUp = (System.nanoTime() - startedAt) / 1_000_000 < 2_000
    return when {
        warmingUp -> 30L
        pool.backlog > 0 -> 20L
        live >= lanes -> 60L
        live > 1 -> 80L
        // 只剩自己一条时也别睡太久：这个分支每多睡一次，
        // 就是在「明明还能切分尾部、却白白空等」的时间上加一笔
        else -> 120L
    }
}

/**
 * 一个 worker 的生命周期：一次只攥着**一小片**区间，干完立刻回池子重新要活儿。
 *
 * ## 区间所有权铁律
 *
 * 一段字节区间在任意时刻**只能有一个持有者**：要么在池子的队列里，
 * 要么在某个 worker 手里 —— **绝不能两边都有**。
 *
 * 老实现是这样写的：手里有一大段时，先把「剩下没取的部分」`putBack` 回池子，
 * 然后把这个 worker 的 `current` 继续指向原来那一大段。于是这段字节
 * **同时存在于池子和 worker 手上**。池子随时可能把它派给另一个 worker，
 * 两个 worker 就会下到同一段、往文件同一偏移重复写盘，
 * 派发总字节会超过文件体积（实测 200MB 的文件多派了 127KB）。
 *
 * 现在改成：切出一小片之后，worker 只持有这一小片，`current` 立刻收窄；
 * 干完就回池子重新取，不再自己攥着剩余部分。
 */
private suspend fun runSlice(
    laneId: Int,
    channel: RouteChannel,
    channels: List<RouteChannel>,
    initial: Chunk,
    pool: SlicePool,
    lanes: Int,
    total: Long,
    output: RandomAccessFile,
    written: AtomicLong,
    accumulator: ProgressAccumulator,
    inflight: CallRegistry,
    board: LaneBoard,
) {
    var current = initial

    while (true) {
        // 被取消就立刻收工，不再取新数据
        currentCoroutineContext().ensureActive()
        if (DownloadEngineFlag.cancelled) return

        val remaining = (total - written.get()).coerceAtLeast(0)

        // 只取这一小片。取满整段时 current.length 本身就不足 target，coerceIn 保证不越界。
        val want = sliceTarget(lanes, total, remaining).coerceIn(1, current.length.toInt())
        val from = current.start
        val to = from + want - 1

        if (to < current.end) {
            // 手里这段比一小片长：把「剩下的」还回池子。
            // 还回去之后，worker 必须立刻放弃对它的所有权 —— 也就是
            // 把 current 收窄成刚切出来的这一小片，绝不再引用后半段。
            pool.putBack(Chunk(0, to + 1, current.end))
            board.splits.incrementAndGet()
        }
        current = Chunk(0, from, to)

        // 每真正派发一片就记一笔，让「完成 / 累计」两个数对得上
        board.totalSlices.incrementAndGet()

        // 派活前先更新看板：面板能立刻看到这条车道换到了哪一段
        board.update(
            LaneSnapshot(
                laneId = laneId,
                routeName = channel.name,
                url = channel.primary,
                start = from,
                end = to,
                downloaded = 0,
                speedBytesPerSecond = channel.measuredSpeed,
                state = SegmentState.DOWNLOADING,
                attempt = 1,
                lastStatus = 206,
            ),
        )
        board.activeUrl = channel.primary

        val outcome: SliceOutcome
        val winner: RouteChannel
        val finalUrl: String
        try {
            val result = fetchSlice(
                chunk = Chunk(0, from, to),
                pool = pool,
                inflight = inflight,
                board = board,
                laneId = laneId,
                channel = channel,
                channels = channels,
            )
            outcome = result.first
            winner = result.second
            finalUrl = result.third
        } catch (e: CancellationException) {
            throw e
        } catch (e: DownloadException.Cancelled) {
            board.remove(laneId)
            return
        } catch (e: Exception) {
            // 这一片重试耗尽（已跨通道试过）：在面板上标红，还回池子，
            // 只有失败预算耗尽才掀桌，避免单线路抖一下就重下几百 MB。
            board.update(
                LaneSnapshot(
                    laneId = laneId,
                    routeName = channel.name,
                    url = channel.primary,
                    start = from,
                    end = to,
                    downloaded = 0,
                    speedBytesPerSecond = 0.0,
                    state = SegmentState.FAILED,
                    attempt = DownloadEngine.MAX_ATTEMPTS,
                    lastStatus = (e as? DownloadException.Throttled)?.code,
                ),
            )
            // 失败的那一段必须还回池子，否则文件会缺一块
            if (current.length > 0) pool.putBack(Chunk(0, from, current.end))
            // 让位退出，而不是向上抛：一条 lane 重试失败不该取消整个下载
            // —— 异常从协程冒出去会连带取消全部 worker。调度器马上会派新的
            // worker 继续吃池子里的区间；失败预算耗尽或 90s 无进展时再判死。
            if (pool.failures.get() > maxSliceFailures(lanes)) throw e
            return
        }

        if (outcome.received > 0) {
            writeAt(output, from, outcome.data, outcome.received)
            written.addAndGet(outcome.received.toLong())
            pool.recordDone(outcome.received.toLong())
            accumulator.advance(outcome.received.toLong())
            winner.observe(outcome.elapsedNanos, outcome.received.toLong())
            board.doneSlices.incrementAndGet()
            board.reward(winner.name)

            val seconds = maxOf(outcome.elapsedNanos / 1_000_000_000.0, 0.001)
            board.update(
                LaneSnapshot(
                    laneId = laneId,
                    routeName = winner.name,
                    url = finalUrl,
                    start = from,
                    end = to,
                    downloaded = outcome.received.toLong(),
                    speedBytesPerSecond = outcome.received / seconds,
                    state = SegmentState.DONE,
                    attempt = 1,
                    lastStatus = 206,
                ),
            )
        }
        if (outcome.received < want) {
            // 没取满（连接中途断了）：把缺的那一段还回池子重取，绝不丢数据
            val missing = Chunk(0, from + outcome.received, to)
            if (missing.length > 0) pool.putBack(missing)
        }

        // 这一小片已经干完，回池子重新要活儿。
        //
        // 注意这里传的 live = 1：代表「我自己还占着一条连接」。
        // 老实现传的是 0，而 splitTail 里 `if (live <= 0) return null`，
        // 于是 worker 自己续做时**永远切不动尾部区间** —— 收尾阶段
        // 池子一空，所有 worker 就只能干等，退化成单连接爬完最后一段。
        current = nextWork(pool, 1, lanes, total) ?: return
    }
}

private data class SliceOutcome(
    val data: ByteArray,
    val received: Int,
    val elapsedNanos: Long,
)

/**
 * 真正发起 Range 请求，把这一小片读进内存（不落临时文件）。
 *
 * 失败时按指数退避重试；命中 429/503 时读 `Retry-After` 退避 ——
 * Azure 单 Blob 有「约 60 MiB/s 或 500 请求/秒」的目标，超了就是 503 ServerBusy，
 * 官方建议用指数退避而不是硬顶，否则会被越限越死。
 *
 * 多线路重试语义（本次修复的核心，与 iOS 同步）：
 *  - attempt 0 用初始通道主地址；
 *  - attempt 1 起重新加权选通道（避开刚失败的那条），试另一条线的 primary；
 *  - 最后一次强制走直连兜底。
 * 全程只用局部变量选地址，不再变异共享 `RouteChannel`，并发重试互不踩。
 * 返回成功时的实际通道，调用方按它做 `observe/reward`，限流标记不张冠李戴。
 *
 * 取消语义：请求登记到 [CallRegistry]，`cancel()` 会把它掐掉；
 * 阻塞读取放在 [withContext] 里，配合 `ensureActive()` 做到「点了就停」。
 * 退避也换成 `delay()`，这样取消能立刻打断等待，而不是睡满再检查。
 */
private suspend fun fetchSlice(
    chunk: Chunk,
    pool: SlicePool,
    inflight: CallRegistry,
    board: LaneBoard,
    laneId: Int,
    channel: RouteChannel,
    channels: List<RouteChannel>,
): Triple<SliceOutcome, RouteChannel, String> {
    var lastError: Exception = DownloadException.BadResponse
    var attempt = 0
    val direct = channels.firstOrNull { it.name == com.artifactboost.app.data.DownloadRoute.DIRECT.name }
    // 限流撞了两回就直接放弃这一片：继续退避 = 攥着区间干等，
    // 整条下载都陪着这条被限流的通道停摆。让位给调度器重新派。
    var throttledCount = 0

    while (attempt < DownloadEngine.MAX_ATTEMPTS) {
        // 每轮重试前先看有没有被取消
        currentCoroutineContext().ensureActive()
        if (DownloadEngineFlag.cancelled) throw DownloadException.Cancelled

        // 选本轮实际通道：首轮用初始，后续换线，最后兜底直连
        val active: RouteChannel
        val url: String
        if (attempt == 0 || channels.size <= 1) {
            active = channel
            url = active.primary
        } else if (attempt >= DownloadEngine.MAX_ATTEMPTS - 1) {
            if (direct != null) {
                active = direct
                url = direct.primary
            } else {
                // plan 里没有直连：用该通道自带的兜底（即直连 URL）
                active = channels[pickRetryChannel(channels, channel.name)]
                url = active.fallback
            }
        } else {
            active = channels[pickRetryChannel(channels, channel.name)]
            url = active.primary
        }
        // 兜底地址即直连：成功不清除镜像的限流标记，归因清晰。
        val isFallbackUrl = url != active.primary
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=${chunk.start}-${chunk.end}")
            .header("User-Agent", GitHubClient.USER_AGENT)
            .build()

        val startedAt = System.nanoTime()
        try {
            // worker 已跑在本次下载的专属线程池上（见 segmentDownload 的 enginePool），
            // 这里直接阻塞执行 —— 不能再丢回 Dispatchers.IO：它只有 64 条线程，
            // lanes 超过 64 时会把实际并发钉死在 64。
            // Call 登记后 cancel() 依然能立刻打断阻塞中的 execute()。
            // 注意用 active.client：本轮可能已换线，用初始通道的连接池就串线了。
            val call = active.client.newCall(request)
            inflight.register(call)
            val data = try {
                call.execute().use { response ->
                    when (response.code) {
                        206 -> Unit
                        200 -> {
                            // 服务器忽略了 Range（回 200 全量）：除了 start==0，
                            // 读到的都是文件头的数据，写到 chunk.start 偏移就是损坏文件。
                            // 当作这条地址不支持分段，换条线重试。
                            if (chunk.start != 0L) throw DownloadException.NoRange
                        }
                        429, 503 -> {
                            pool.throttles.incrementAndGet()
                            board.throttles.incrementAndGet()
                            // 通道级降额：不是简单降权重，而是直接把它判为「被限流」，
                            // 调度器下一轮就会削它的并发，避免越限越死。
                            active.throttled = true
                            board.penalize(active.name)
                            throw DownloadException.Throttled(
                                response.code,
                                DownloadEngine.retryAfter(response.headers),
                            )
                        }
                        else -> throw DownloadException.BadResponse
                    }
                    // 兜底（直连）成功不代表镜像恢复，不清除镜像限流标记
                    if (!isFallbackUrl) active.throttled = false
                    val body = response.body ?: throw DownloadException.BadResponse
                    val buffer = ByteArray(chunk.length.toInt())
                    var received = 0
                    body.byteStream().use { input ->
                        while (received < buffer.size) {
                            val read = input.read(buffer, received, buffer.size - received)
                            if (read <= 0) break
                            received += read
                        }
                    }
                    if (received <= 0) throw DownloadException.Incomplete
                    buffer to received
                }
            } finally {
                inflight.release(call)
            }
            return Triple(SliceOutcome(data.first, data.second, System.nanoTime() - startedAt), active, url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 取消导致的中断：统一翻译成 Cancelled，不要被当成网络错误去重试
            if (e is DownloadException.Cancelled || DownloadEngineFlag.cancelled) {
                throw DownloadException.Cancelled
            }
            lastError = e
            attempt++
            board.retries.incrementAndGet()
            if (attempt >= DownloadEngine.MAX_ATTEMPTS) break

            // 不支持 Range 的地址：下轮循环会自动换线重试，不退避 —— 这是地址选错了，
            // 不是服务器忙（attempt 照常消耗，单地址轮到自己时也能正常退出）。
            // endpoints 不可变，不再原地轮换，换线由选路负责。
            if (e is DownloadException.NoRange) {
                continue
            }

            if (e is DownloadException.Throttled) {
                // 被限流：把这条通道的权重降下来，让活儿分给别人
                active.measuredSpeed = maxOf(active.measuredSpeed * 0.5, 1.0)
                throttledCount++
                if (throttledCount >= 2) {
                    // 连着两次限流：这条通道眼下进不去，别攥着区间长睡，
                    // 直接放弃这一片 —— 调度器马上会把活儿派给健康通道。
                    break
                }
            }

            // 重试状态同步到面板：用户能看到「车道 #3 正在第 2 次重试 / 上一次 503」
            board.update(
                LaneSnapshot(
                    laneId = laneId,
                    routeName = active.name,
                    url = url,
                    start = chunk.start,
                    end = chunk.end,
                    downloaded = 0,
                    speedBytesPerSecond = 0.0,
                    state = SegmentState.RETRYING,
                    attempt = attempt + 1,
                    lastStatus = (e as? DownloadException.Throttled)?.code,
                ),
            )

            // 退避用 delay 而不是 Thread.sleep：取消能立刻打断等待
            delay(DownloadEngine.backoffMillis(attempt, e))
        }
    }

    pool.failures.incrementAndGet()
    throw lastError
}

private fun writeAt(output: RandomAccessFile, offset: Long, data: ByteArray, length: Int) {
    synchronized(output) {
        output.seek(offset)
        output.write(data, 0, length)
    }
}

/**
 * 按实时吞吐加权挑通道：快的多干活，慢的也有活（带宽叠加，与 iOS 同步）。
 *
 * 老实现是贪心取最快，导致所有 lane 挤在同一条通道/同一连接池，
 * 既打爆单镜像（429/503）又浪费其它通道带宽。
 */
private fun pickChannel(channels: List<RouteChannel>, hint: Int): Int {
    if (channels.size == 1) return 0
    var total = 0.0
    val weights = DoubleArray(channels.size)
    for ((i, ch) in channels.withIndex()) {
        var w = maxOf(ch.measuredSpeed, 1.0)
        // 被限流的通道降权 90%，而不是直接剔除（保底不断流）
        if (ch.throttled) w *= 0.1
        weights[i] = w
        total += w
    }
    if (total <= 0) return hint % channels.size
    var r = Math.random() * total
    for (i in weights.indices) {
        r -= weights[i]
        if (r <= 0) return i
    }
    return weights.indices.maxByOrNull { weights[it] } ?: 0
}

/**
 * 重试选路：加权随机，但排除刚失败的那条线。
 * 排除是为了“首失败即换线”：一直加权随机仍可能连抽同一条坏线，
 * 白白浪费 `MAX_ATTEMPTS` 里宝贵的第二次机会。
 */
private fun pickRetryChannel(channels: List<RouteChannel>, excluding: String): Int {
    if (channels.size == 1) return 0
    var total = 0.0
    val weights = DoubleArray(channels.size)
    for ((i, ch) in channels.withIndex()) {
        if (ch.name == excluding) {
            weights[i] = 0.0
            continue
        }
        var w = maxOf(ch.measuredSpeed, 1.0)
        if (ch.throttled) w *= 0.1
        weights[i] = w
        total += w
    }
    // 被排除后无可用（同名通道占满）：退回普通加权
    if (total <= 0) return pickChannel(channels, (Math.random() * channels.size).toInt())
    var r = Math.random() * total
    for (i in weights.indices) {
        r -= weights[i]
        if (r <= 0) return i
    }
    return weights.indices.maxByOrNull { weights[it] } ?: 0
}

/** 单片耗尽重试（已跨通道）后不立刻掀桌，攒够这么多才判死（与 iOS 同步）。 */
internal fun maxSliceFailures(lanes: Int): Int = maxOf(20, lanes * 2)

/** 用一小片实测吞吐更新通道速度（滑动平均，避免抖动） */
private fun RouteChannel.observe(elapsedNanos: Long, bytes: Long) {
    val seconds = elapsedNanos / 1_000_000_000.0
    if (seconds < 0.02 || bytes <= 0) return
    val instant = bytes / seconds
    measuredSpeed = if (measuredSpeed <= 0) instant else measuredSpeed * 0.7 + instant * 0.3
}

/** 取消标志的载体：分片函数是顶层函数，拿不到引擎实例 */
internal object DownloadEngineFlag {
    @Volatile var cancelled = false
}

/**
 * 待下载区间的池子（滑动窗口）。
 *
 * 关键设计：区间只记 (start, end)，不带全局编号 ——
 * 于是「把一段砍成两半」不需要给任何 worker 重新编号，
 * 写盘也能按偏移随意定位。这正是「随时切分、随时抢活」的前提。
 */
internal class SlicePool(val total: Long, slices: Int = 1) {

    private val queue = ConcurrentLinkedDeque<Chunk>()

    /** 每次「把末尾区间砍一刀」记一笔 */
    val splits = AtomicInteger(0)

    /** 池子里还剩几段区间 */
    val backlog: Int get() = queue.size

    val failures = AtomicInteger(0)
    val throttles = AtomicInteger(0)

    /** 已完成的字节数：用于估算剩余量、决定分片粒度 */
    private val completed = AtomicLong(0)

    fun downloaded(): Long = completed.get()

    fun recordDone(bytes: Long) {
        completed.addAndGet(bytes)
    }

    init {
        // 预切分：首轮就能派满 lanes 条连接，避免单区间导致的调度饿死（与 iOS 同步）。
        // 按 lanes*4 均分，尾块吃余数；块太小（<64KB）时自动收敛，避免任务爆炸。
        // 默认 slices=1 保持旧单测兼容。
        val target = maxOf(1, slices)
        if (target <= 1 || total <= 0) {
            queue.add(Chunk(0, 0L, total - 1))
        } else {
            val maxSlices = maxOf(1, (total / (64 * 1024)).toInt())
            val count = maxOf(1, minOf(target, maxSlices))
            if (count <= 1) {
                queue.add(Chunk(0, 0L, total - 1))
            } else {
                val base = total / count
                var start = 0L
                for (i in 0 until count) {
                    val end = if (i == count - 1) total - 1 else start + base - 1
                    queue.add(Chunk(0, start, end))
                    start = end + 1
                }
            }
        }
    }

    /** 取一段活儿；没有就返回 null，由调度循环决定要不要切分 */
    fun take(): Chunk? {
        val chunk = queue.pollFirst()
        checkInvariants()
        return chunk
    }

    /** 把没下完的区间还回队列最前面 */
    fun putBack(chunk: Chunk) {
        queue.addFirst(chunk)
        checkInvariants()
    }

    /**
     * 区间所有权不变量自检（仅 debug 构建生效，release 下整个函数体被编译器消掉）。
     *
     * 校验两件事：
     *  1. 池内所有区间两两不重叠 —— 一旦重叠，两个 worker 会下同一段、重复写盘；
     *  2. 所有区间都落在 `[0, total)` 内 —— 越界写会直接损坏文件。
     *
     * 这条断言就是为「派发总字节超过文件体积」那个 bug 加的防线：
     * 以后谁再动调度逻辑，测试没覆盖到的地方也能在 debug 跑挂暴露出来。
     */
    private fun checkInvariants() {
        if (!ENABLE_INVARIANTS) return
        val snapshot = queue.toList().sortedBy { it.start }
        var prevEnd = -1L
        for (chunk in snapshot) {
            check(chunk.start in 0 until total) {
                "区间起点越界: ${chunk.start} 不在 [0, $total)"
            }
            check(chunk.end in 0 until total) {
                "区间终点越界: ${chunk.end} 不在 [0, $total)"
            }
            check(chunk.start <= chunk.end) {
                "区间非法: ${chunk.start} > ${chunk.end}"
            }
            check(chunk.start > prevEnd) {
                "区间重叠: 上一段结束于 $prevEnd，这一段却从 ${chunk.start} 开始"
            }
            prevEnd = chunk.end
        }
    }

    /**
     * 池子空了、但还有连接闲着时调用：
     * 从队列末尾挑一段最大的砍成两半 ——
     * 右半段留在池子里，左半段直接返回给这个空闲连接。
     */
    fun splitTail(live: Int, target: Int): Chunk? {
        if (live <= 0) return null

        val victim = queue.pollLast() ?: return null
        if (victim.length <= target.toLong()) {
            // 已经切到目标粒度了，别再无谓地碎片化
            queue.addLast(victim)
            checkInvariants()
            return null
        }

        val half = victim.length / 2
        queue.addLast(Chunk(0, victim.start + half, victim.end))
        splits.incrementAndGet()
        checkInvariants()
        return Chunk(0, victim.start, victim.start + half - 1)
    }
}

/**
 * 是否开启 [SlicePool] 的区间不变量自检。
 *
 * 只在 debug 构建打开：release 下 `checkInvariants()` 会在第一次 `if` 就返回，
 * 连 `queue.toList()` 的分配都省掉，零运行时开销。
 */
private val ENABLE_INVARIANTS: Boolean =
    runCatching { Class.forName("com.artifactboost.app.BuildConfig") }.isSuccess

/**
 * 汇总各分块进度，节流后回调给 UI。
 * 速度用滑动平均避免数字乱跳；但连续几拍零增长时必须往下压，
 * 否则界面会一直挂着峰值速度、而实际已经掉下去了。
 */
class ProgressAccumulator(
    private val total: Long,
    private val handler: (DownloadProgress) -> Unit,
    /** 快照来源：每拍现取一次车道看板，拿到的就是「此刻」而不是「启动时」的明细 */
    private val diagnostics: (() -> DownloadDiagnostics?)? = null,
) {
    @Volatile private var downloaded = 0L
    @Volatile private var lastEmit = 0L
    @Volatile private var lastSampleTime = System.nanoTime()
    @Volatile private var lastSampleBytes = 0L
    @Volatile private var smoothedSpeed = 0.0
    @Volatile private var zeroStreak = 0

    fun advance(bytes: Long) {
        val now = System.currentTimeMillis()
        val current = downloaded + bytes
        downloaded = current
        val shouldEmit = synchronized(this) {
            if (now - lastEmit < 250L) return@synchronized false
            lastEmit = now
            true
        }
        if (shouldEmit) handler(snapshot(current))
    }

    fun finish(downloaded: Long) {
        this.downloaded = downloaded
        val finalTotal = maxOf(total, downloaded)
        handler(
            DownloadProgress(
                downloadedBytes = finalTotal,
                totalBytes = finalTotal,
                fraction = 1f,
                speedBytesPerSecond = maxOf(smoothedSpeed, 0.0),
            ),
        )
    }

    private fun snapshot(current: Long): DownloadProgress {
        val now = System.nanoTime()
        val dt = (now - lastSampleTime) / 1_000_000_000.0
        if (dt > 0.05) {
            val delta = current - lastSampleBytes
            if (delta <= 0) {
                zeroStreak++
                // 连续 3 拍没涨（约 0.75s 零吞吐）：平滑值必须往下压
                if (zeroStreak >= 3) smoothedSpeed *= 0.4
            } else {
                zeroStreak = 0
                val instant = delta / dt
                smoothedSpeed = if (smoothedSpeed <= 0) instant else smoothedSpeed * 0.6 + instant * 0.4
            }
            lastSampleTime = now
            lastSampleBytes = current
        }
        return DownloadProgress(
            downloadedBytes = current,
            totalBytes = total,
            fraction = if (total > 0) minOf(current.toFloat() / total, 1f) else 0f,
            speedBytesPerSecond = maxOf(smoothedSpeed, 0.0),
            diagnostics = diagnostics?.invoke(),
        )
    }
}
