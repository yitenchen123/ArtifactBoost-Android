package com.artifactboost.app.download

/**
 * 按可用堆内存约束并发数。
 *
 * ## 为什么必须做这件事
 *
 * 分段下载里每个 worker 都会为一个分片 `new ByteArray(分片大小)`，
 * 分片大小上限是 `MAX_SLICE_TARGET`（4MB）。用户把并发设到 128 时：
 *
 *     128 × 4MB = 512MB 瞬时峰值
 *
 * 这在 Android 上必然 OOM 或者触发疯狂 GC —— 表现就是「小文件没事、
 * 大文件完全下不动 / 卡死 / 闪退」。而且大文件分片本来就容易取满上限，
 * 小文件因为总量小反而不会。
 *
 * 这里按「当前可用堆 / 单个分片预算」算出安全并发，再与用户设的上限取小。
 * 宁可少开几条连接（速度略降），也不能让整个下载崩掉。
 */
internal object MemoryBudget {

    /** 给分片缓冲预留的可用堆比例：只敢用一半，另一半留给框架、GC、文件缓冲等 */
    private const val HEAP_FRACTION = 0.5

    /** 兜底下限：再省也得留 2 条连接，否则等于退化成单线程 */
    private const val MIN_LANES = 2

    /**
     * 计算安全并发数。
     *
     * @param requested 用户设定的并发上限
     * @param bytesPerLane 每条连接预计占用的分片缓冲字节数
     */
    fun safeLanes(requested: Int, bytesPerLane: Long): Int {
        if (requested <= MIN_LANES) return requested.coerceAtLeast(1)

        val available = usableHeapBytes()
        if (available <= 0) return requested   // 拿不到堆信息就别乱改

        val perLane = maxOf(bytesPerLane, 1L)
        val byHeap = (available / perLane).toInt()
        return byHeap.coerceIn(MIN_LANES, requested)
    }

    /**
     * 估算可用堆字节数。
     *
     * 注意这里要点：
     *  - 用 `maxMemory - totalMemory + freeMemory`（JVM 已经分配的堆里还没用的
     *    部分 + 还能继续扩张的部分），而不是只看 `freeMemory()` ——
     *    后者会把「堆还能长大」的空间算成不可用，导致并发被压得过低。
     *  - 再乘一个安全系数，避免贴着上限跑。
     */
    private fun usableHeapBytes(): Long {
        val rt = Runtime.getRuntime()
        val max = rt.maxMemory()
        val total = rt.totalMemory()
        val free = rt.freeMemory()
        // 堆里当前可用的部分
        val inHeap = free
        // 堆还能继续扩张到的空间
        val growable = (max - total).coerceAtLeast(0)
        val raw = inHeap + growable
        if (raw <= 0) return 0
        return (raw * HEAP_FRACTION).toLong()
    }
}
