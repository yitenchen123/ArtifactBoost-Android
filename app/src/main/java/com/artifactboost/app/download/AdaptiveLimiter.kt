package com.artifactboost.app.download

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 自适应并发控制器（AIMD 拥塞窗口 + 每秒请求数速率闸）。
 *
 * 解决的问题：用户把并发拉到 128，引擎就真的往服务器砸 128 条连接 ——
 * Azure Blob 对单 Blob 有「约 60 MiB/s 或 500 请求/秒」的软目标，
 * 超了直接回 503 ServerBusy；很多公共镜像更狠，几十条就 429。
 * 一旦被限流，用户看到的就是「越下越慢、时不时卡住」。
 *
 * 这里做两件事：
 *  1. **AIMD 拥塞窗口**（和 TCP 一个思路）：顺畅时*加性增*，命中限流时*乘性减*（砍半）。
 *     于是实际并发会自己爬到「服务器愿意给的那个上限」然后稳在那里，
 *     而不是硬顶着 128 撞墙。
 *  2. **全局速率闸**：把「每秒发出的 Range 请求数」也管起来。窗口管的是瞬时并发，
 *     速率闸管的是 QPS —— 两者一起才能压住 429。
 *
 * 用户设的 `connections` 从此只是**上限**而不是**目标**：设 128 不会更慢，
 * 只意味着「允许引擎在服务器撑得住时爬到 128」。
 *
 * 并发安全：所有状态都是原子的，调度线程（单线程）与 worker 线程都会调用。
 */
internal class AdaptiveConcurrency(val ceiling: Int) {

    /** 当前窗口：实际允许的在飞请求数 */
    private val window = AtomicInteger(initialWindow())

    /** 上次乘性减半的时间戳（ms）：避免一次限流风暴把窗口一路打到 1 */
    private val lastDecreaseAt = AtomicLong(0)

    /** 上次「顺畅」的时间戳（ms）：连续顺畅够久就退出探测期 */
    private val smoothSince = AtomicLong(System.currentTimeMillis())

    /** 处于「探测期」（刚被限流砍过窗口，涨幅收窄） */
    @Volatile private var probing = false

    /** 结果计数（诊断面板用） */
    private val increases = AtomicInteger(0)
    private val decreases = AtomicInteger(0)
    private val peak = AtomicInteger(0)

    /** 速率闸：最近一秒内发出的请求时间戳（环形近似用列表 + 裁剪） */
    private val emitNanos = ArrayDeque<Long>()

    /** 每秒允许发出的请求数上限（随窗口缩放） */
    @Volatile private var maxQps: Int = initialWindow() * QPS_PER_LANE

    private fun initialWindow(): Int = minOf(RAMP_START, ceiling).coerceAtLeast(1)

    val currentWindow: Int get() = window.get()
    val windowIncreases: Int get() = increases.get()
    val windowDecreases: Int get() = decreases.get()
    val windowPeak: Int get() = peak.get()

    /**
     * 判定「现在能不能再派一片」：既要窗口没满，也要没撞上速率闸。
     * @param inflight 当前在飞请求数
     */
    @Synchronized
    fun canDispatch(inflight: Int): Boolean {
        if (inflight >= window.get()) return false
        return qpsRoomLocked() > 0
    }

    /** 记录一次「即将发出请求」，用于速率闸计数 */
    @Synchronized
    fun noteDispatch() {
        emitNanos.addLast(System.nanoTime())
        trimLocked()
    }

    /**
     * 一片成功：加性增（AIMD 的 AI 部分）。
     *
     * 分两个阶段：**探测期**（刚被限流砍过窗口）每片 +1，
     * **稳定期**（连续顺畅超过 [SMOOTH_THRESHOLD_MS]）每片 +2。
     * 探测期步子小，避免窗口刚被砍完就立刻冲回去。
     */
    @Synchronized
    fun noteSuccess() {
        val now = System.currentTimeMillis()
        val wasProbing = probing
        if (now - smoothSince.get() >= SMOOTH_THRESHOLD_MS) probing = false

        if (window.get() < ceiling) {
            val step = if (wasProbing) 1 else 2
            window.updateAndGet { minOf(it + step, ceiling) }
            increases.incrementAndGet()
            maxQps = window.get() * QPS_PER_LANE
        }
        peak.updateAndGet { maxOf(it, window.get()) }
        smoothSince.set(now)
    }

    /**
     * 命中限流（429/503）：乘性减（AIMD 的 MD 部分）。
     *
     * 同一秒内不重复减半 —— 否则 128 条连接同时撞 429 时，窗口会被连续减半 7 次
     * 直接掉到 1，之后的加性增要爬很久才能恢复，用户体感就是「限流之后一直很慢」。
     */
    @Synchronized
    fun noteThrottle() {
        val now = System.currentTimeMillis()
        if (now - lastDecreaseAt.get() > 1000L) {
            decreases.incrementAndGet()
            window.updateAndGet { maxOf(2, it / 2) }
            maxQps = window.get() * QPS_PER_LANE
            lastDecreaseAt.set(now)
            probing = true
            smoothSince.set(now)
        }
    }

    /** 一片超时/连接错误（非限流）：轻度收缩，避免在坏线上继续加压 */
    @Synchronized
    fun noteFailure() {
        val now = System.currentTimeMillis()
        if (now - lastDecreaseAt.get() > 500L && window.get() > 4) {
            window.updateAndGet { maxOf(4, it - maxOf(1, it / 8)) }
            maxQps = window.get() * QPS_PER_LANE
            lastDecreaseAt.set(now)
            smoothSince.set(now)
        }
    }

    /** 调度循环把实时状态同步进看板（诊断面板显示用） */
    fun snapshotInto(board: LaneBoard) {
        board.syncWindow(
            current = window.get(),
            increases = increases.get(),
            decreases = decreases.get(),
            peak = peak.get(),
        )
    }

    /** 速率闸余额（调用方必须已持锁） */
    private fun qpsRoomLocked(): Int {
        trimLocked()
        return maxOf(maxQps - emitNanos.size, 0)
    }

    /** 只保留最近 1 秒的发送记录 */
    private fun trimLocked() {
        val cutoff = System.nanoTime() - 1_000_000_000L
        while (emitNanos.isNotEmpty() && emitNanos.first() <= cutoff) {
            emitNanos.removeFirst()
        }
    }

    private companion object {
        /** 起始窗口：不激进也不保守，与老实现的爬坡起点一致 */
        const val RAMP_START = 16

        /** 每个并发每秒最多发这么多个请求（Azure 单 Blob 500 QPS 的量级，留出余量） */
        const val QPS_PER_LANE = 8

        /** 连续顺畅多久才退出探测期（之后涨幅加倍） */
        const val SMOOTH_THRESHOLD_MS = 3_000L
    }
}
