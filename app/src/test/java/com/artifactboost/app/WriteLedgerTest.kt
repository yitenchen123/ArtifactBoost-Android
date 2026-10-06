package com.artifactboost.app

import com.artifactboost.app.download.WriteLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * 写入覆盖账本的测试。
 *
 * 这块逻辑直接决定「下载完的文件到底对不对」：
 * 老的完整性校验只有 `written == total`（总量），而「重复写一段 + 漏写一段」
 * 的总量完全可能相等 —— 文件大小对、内容错，用户拿去解压就失败了。
 * 这些用例把「每个字节恰好被写一次」这条不变量钉死。
 */
class WriteLedgerTest {

    @Test
    fun `顺序写入完整覆盖后判定完成`() {
        val ledger = WriteLedger(total = 1000)
        ledger.record(0, 499)
        ledger.record(500, 999)
        assertTrue(ledger.isComplete())
        assertEquals(1000, ledger.coveredBytes)
        assertEquals(0, ledger.overlaps.get())
    }

    @Test
    fun `乱序写入也能正确合并`() {
        val ledger = WriteLedger(total = 1000)
        ledger.record(500, 999)
        ledger.record(0, 249)
        ledger.record(250, 499)
        assertTrue(ledger.isComplete())
        assertEquals(1000, ledger.coveredBytes)
    }

    @Test
    fun `缺一段时判定不完整`() {
        val ledger = WriteLedger(total = 1000)
        ledger.record(0, 499)
        ledger.record(600, 999)   // 500~599 没写
        assertFalse("缺 100 字节却判定完成 = 会产生损坏文件", ledger.isComplete())
        assertEquals(900, ledger.coveredBytes)

        val gaps = ledger.gaps()
        assertEquals(1, gaps.size)
        assertEquals(500L, gaps[0].first)
        assertEquals(599L, gaps[0].last)
    }

    @Test
    fun `重复写入会被记成重叠且不虚增覆盖量`() {
        val ledger = WriteLedger(total = 1000)
        ledger.record(0, 499)
        val newly = ledger.record(0, 499)   // 完全重复
        assertEquals("重复写入不该增加覆盖量", 0L, newly)
        assertEquals(500, ledger.coveredBytes)
        assertEquals("必须检测到重叠", 1, ledger.overlaps.get())
        assertFalse("有重叠就不能判定完成", ledger.isComplete())
    }

    @Test
    fun `部分重叠时只统计新增部分`() {
        val ledger = WriteLedger(total = 1000)
        ledger.record(0, 499)
        val newly = ledger.record(400, 699)  // 与已有区间重叠 100 字节
        assertEquals("只应新增 400~699 里没写过的 200 字节", 200L, newly)
        assertEquals(700, ledger.coveredBytes)
        assertEquals(1, ledger.overlaps.get())
    }

    @Test
    fun `总量相等但内容不同的场景必须被识破`() {
        // 这是最容易骗过「只校验总量」的那类 bug：
        // 写了两遍 [0,499]，一遍 [500,999]，总量恰好 1500 —— 但 [0,499] 被覆盖两次，
        // 实际文件里有一段是脏的。老实现只看 written == total，会放行。
        val total = 1500L
        val written = java.util.concurrent.atomic.AtomicLong(0)
        val ledger = WriteLedger(total)

        written.addAndGet(500); ledger.record(0, 499)
        written.addAndGet(500); ledger.record(0, 499)      // 重复
        written.addAndGet(500); ledger.record(500, 999)

        assertEquals("总量看起来正好「够」", total, written.get())
        assertFalse("但覆盖校验必须判它不完整", ledger.isComplete())
        assertTrue("应当检测到重复写入", ledger.overlaps.get() > 0)
    }

    @Test
    fun `随机分片顺序写入仍能完整覆盖`() {
        val total = 1_000_000L
        val ledger = WriteLedger(total)

        // 造出互不重叠的分片，再打乱顺序写入
        val pieces = mutableListOf<Pair<Long, Long>>()
        var cursor = 0L
        val rnd = Random(42)
        while (cursor < total) {
            val len = (1 + rnd.nextInt(50_000)).toLong()
            val end = minOf(cursor + len - 1, total - 1)
            pieces.add(cursor to end)
            cursor = end + 1
        }
        pieces.shuffled(rnd).forEach { (s, e) -> ledger.record(s, e) }

        assertTrue("无论什么顺序，覆盖齐全就该判定完成", ledger.isComplete())
        assertEquals(total, ledger.coveredBytes)
        assertEquals(0, ledger.overlaps.get())
    }

    @Test
    fun `缺口的描述里带上具体字节数`() {
        val ledger = WriteLedger(total = 2000)
        ledger.record(0, 999)
        val text = ledger.describe()
        assertTrue("描述里应当有已覆盖/总量", text.contains("1000/2000"))
        assertTrue("描述里应当点出缺口", text.contains("[1000-1999]"))
    }

    @Test
    fun `相邻区间会被正确合并成一段`() {
        val ledger = WriteLedger(total = 300)
        ledger.record(0, 99)
        ledger.record(100, 199)
        ledger.record(200, 299)
        assertTrue(ledger.isComplete())
        assertEquals("相邻片段应合并，不该碎片化", 1, ledger.segmentCount)
    }

    @Test
    fun `零字节与非法区间的写入被忽略`() {
        val ledger = WriteLedger(total = 100)
        assertEquals(0L, ledger.record(10, 9))     // end < start
        assertEquals(0, ledger.coveredBytes)
        assertFalse(ledger.isComplete())
    }
}
