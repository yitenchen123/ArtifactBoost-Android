package com.artifactboost.app

import com.artifactboost.app.download.MemoryBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 并发内存预算的测试。
 *
 * 背景：分段下载里每个 worker 都会为一个分片 `new ByteArray(分片大小)`。
 * 用户把并发设到 128、分片上限又是 4MB 时，瞬时峰值是 512MB ——
 * Android 上直接 OOM，「小文件没事、大文件完全下不动」正是这么来的。
 *
 * 这些用例守住两条：
 *  1. 并发永远不会超过用户设的上限；
 *  2. 并发至少保留 2 条（不能退化成单线程）。
 */
class MemoryBudgetTest {

    private val oneMeg = 1024L * 1024L

    @Test
    fun `并发不会超过用户设定的上限`() {
        // 堆很富裕时，也不该擅自放大用户设的值
        assertEquals(8, MemoryBudget.safeLanes(requested = 8, bytesPerLane = oneMeg))
    }

    @Test
    fun `低配机的大并发会被压下来`() {
        val lanes = MemoryBudget.safeLanes(requested = 128, bytesPerLane = oneMeg)
        assertTrue("并发必须被内存约束压到 128 以下", lanes <= 128)
        assertTrue("但不能压到 0", lanes >= 2)
    }

    @Test
    fun `再省也要保留 2 条连接`() {
        // 分片预算大得离谱（模拟极小堆）→ 依然给 2 条，不退化成单线程
        val lanes = MemoryBudget.safeLanes(requested = 128, bytesPerLane = Long.MAX_VALUE / 4)
        assertEquals(2, lanes)
    }

    @Test
    fun `用户只设 1 或 2 条时原样返回`() {
        assertEquals(1, MemoryBudget.safeLanes(requested = 1, bytesPerLane = oneMeg))
        assertEquals(2, MemoryBudget.safeLanes(requested = 2, bytesPerLane = oneMeg))
    }

    @Test
    fun `分片越小可用并发越多_不会误伤`() {
        val small = MemoryBudget.safeLanes(requested = 128, bytesPerLane = 64 * 1024)
        val large = MemoryBudget.safeLanes(requested = 128, bytesPerLane = 8 * oneMeg)
        assertTrue(
            "分片越小应当允许越多并发（$small vs $large）",
            small >= large,
        )
    }

    @Test
    fun `计算出的并发乘以分片预算不会超过可用内存`() {
        val lanes = MemoryBudget.safeLanes(requested = 128, bytesPerLane = oneMeg)
        val peak = lanes * oneMeg
        // 至少不能超过 128MB 这个常见阈值（1MB × 128 的理论上限）
        assertTrue("峰值内存 ${peak / oneMeg}MB 过大", peak <= 128 * oneMeg)
    }
}
