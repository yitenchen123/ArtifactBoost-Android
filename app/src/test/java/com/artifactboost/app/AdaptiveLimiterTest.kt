package com.artifactboost.app

import com.artifactboost.app.download.AdaptiveConcurrency
import com.artifactboost.app.download.Chunk
import com.artifactboost.app.download.SlicePool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自适应并发（AIMD）与失败退避的纯逻辑测试。
 *
 * 这两块是这次「128 并发被限流 / 时不时卡住」修复的核心：
 *  - AIMD 窗口决定「引擎到底开多少条连接」；
 *  - 失败退避决定「被限流的片会不会立刻被重取、把下载憋成热循环」。
 */
class AdaptiveLimiterTest {

    @Test
    fun `起始窗口不超过用户设定的上限`() {
        assertEquals(16, AdaptiveConcurrency(ceiling = 64).currentWindow)
        // 上限比 16 还小时直接顶满，不该超过它
        assertEquals(4, AdaptiveConcurrency(ceiling = 4).currentWindow)
        assertEquals(1, AdaptiveConcurrency(ceiling = 1).currentWindow)
    }

    @Test
    fun `顺畅时会加性增但永不超过上限`() {
        val limiter = AdaptiveConcurrency(ceiling = 20)
        repeat(100) { limiter.noteSuccess() }
        assertEquals("窗口必须被上限封顶", 20, limiter.currentWindow)
        assertTrue(limiter.windowIncreases > 0)
    }

    @Test
    fun `命中限流会乘性减半`() {
        val limiter = AdaptiveConcurrency(ceiling = 128)
        repeat(64) { limiter.noteSuccess() }
        val before = limiter.currentWindow
        assertTrue("起手窗口应该涨上去过", before > 16)

        limiter.noteThrottle()
        assertEquals("限流应当砍半", before / 2, limiter.currentWindow)
        assertTrue(limiter.windowDecreases >= 1)
    }

    @Test
    fun `同一秒内连续限流只减半一次_避免窗口被打到 1`() {
        val limiter = AdaptiveConcurrency(ceiling = 128)
        repeat(100) { limiter.noteSuccess() }
        val before = limiter.currentWindow

        // 128 条连接同时撞 429 的场面：连续 10 次限流通知
        repeat(10) { limiter.noteThrottle() }

        assertEquals(
            "同一秒内应当只砍一次，否则窗口会掉到 1、之后爬很久才恢复",
            before / 2,
            limiter.currentWindow,
        )
        assertEquals(1, limiter.windowDecreases)
    }

    @Test
    fun `窗口永远不会降到 2 以下`() {
        val limiter = AdaptiveConcurrency(ceiling = 128)
        repeat(20) {
            limiter.noteThrottle()
            Thread.sleep(1100) // 越过 1 秒冷却，让每次限流都真的生效
        }
        assertTrue("窗口下限必须保住 2，否则并发塌成单连接", limiter.currentWindow >= 2)
    }

    @Test
    fun `速率闸会限制每秒派发数`() {
        val limiter = AdaptiveConcurrency(ceiling = 16)
        val window = limiter.currentWindow
        // 起始 QPS 上限 = 窗口 * 8
        var allowed = 0
        repeat(window * 8 + 50) {
            if (limiter.canDispatch(inflight = 0)) {
                limiter.noteDispatch()
                allowed++
            }
        }
        assertEquals("一秒内的派发量应当被速率闸卡住", window * 8, allowed)
    }

    @Test
    fun `窗口满了就不再派发`() {
        val limiter = AdaptiveConcurrency(ceiling = 16)
        assertFalse(limiter.canDispatch(inflight = limiter.currentWindow))
        assertTrue(limiter.canDispatch(inflight = limiter.currentWindow - 1))
    }
}

/**
 * 失败退避的测试 —— 守住「失败片不会立刻被重取」这条不变量。
 *
 * 老实现把失败的片 `addFirst` 塞回队首，下一个空闲 worker 立刻又捞走重试；
 * 服务器正在限流时这就成了吞吐为零的热循环，用户看到的就是「卡住」。
 */
class SlicePoolBackoffTest {

    @Test
    fun `带退避还回的区间在到期前不可见`() {
        val pool = SlicePool(total = 4L * 1024 * 1024)
        val work = pool.take()!!
        // 先确认池子空了
        assertNull(pool.take())

        // 带 5 秒退避还回
        pool.putBack(work, backoffMs = 5_000L)

        assertNull("退避期内的区间不该被取走", pool.take())
        assertEquals("应当被算作「还在退避」", 1, pool.deferredCount())
    }

    @Test
    fun `退避到期后可以正常取走`() {
        val pool = SlicePool(total = 4L * 1024 * 1024)
        val work = pool.take()!!
        pool.putBack(work, backoffMs = 50L)

        Thread.sleep(120)
        val again = pool.take()
        assertNotNull("退避到期后应当可以再取", again)
        assertEquals(work, again)
        assertEquals(0, pool.deferredCount())
    }

    @Test
    fun `退避中的区间不会被切分`() {
        val pool = SlicePool(total = 64L * 1024 * 1024)
        val whole = pool.take()!!
        pool.putBack(whole, backoffMs = 5_000L)

        assertNull("退避中的区间不该被 splitTail 拿去切", pool.splitTail(live = 4, target = 128 * 1024))
    }

    @Test
    fun `退避中的区间不阻塞其它可用区间被取走`() {
        val total = 8L * 1024 * 1024
        val pool = SlicePool(total)
        // 先把整段取出来，再切成互不重叠的两段还回去
        val whole = pool.take()!!
        val half = whole.length / 2

        // 后半段带退避（模拟失败片），前半段正常可用
        pool.putBack(Chunk(0, whole.start + half, whole.end), backoffMs = 5_000L)
        pool.putBack(Chunk(0, whole.start, whole.start + half - 1))

        val work = pool.take()
        assertNotNull("退避的条目不该挡住正常条目", work)
        assertEquals("应当拿到那个可用的区间（前半段）", 0L, work!!.start)
        assertEquals("拿到的应当是前半段", whole.start + half - 1, work.end)
    }
}
