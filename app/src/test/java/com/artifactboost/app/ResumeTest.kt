package com.artifactboost.app

import com.artifactboost.app.download.SlicePool
import com.artifactboost.app.download.WriteLedger
import com.artifactboost.app.download.gapsOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 断点续传的纯逻辑测试：缺口计算 / 缺口池 / 账本种子。
 *
 * 这三块是「续传不写错位置」的地基 —— 一旦算错，
 * 表现就是下载"成功"但文件内容错位、解压失败。
 */
class ResumeTest {

    @Test
    fun `没有任何已覆盖区间时，缺口就是整段`() {
        assertEquals(listOf(0L..999L), gapsOf(1000L, emptyList()))
    }

    @Test
    fun `已覆盖开头和中间时，缺口是剩余部分`() {
        val gaps = gapsOf(1000L, listOf(0L..99L, 500L..599L))
        assertEquals(listOf(100L..499L, 600L..999L), gaps)
    }

    @Test
    fun `已覆盖到末尾时没有缺口`() {
        val gaps = gapsOf(1000L, listOf(0L..999L))
        assertTrue(gaps.isEmpty())
    }

    @Test
    fun `越界的旧区间会被忽略，不会造出非法缺口`() {
        // 90..200 超出了文件长度：整段丢弃（宁可多下一次，也不写到越界位置）
        val gaps = gapsOf(100L, listOf(0L..49L, 90L..200L))
        assertEquals(listOf(50L..99L), gaps)
    }

    @Test
    fun `缺口池只派发缺口区间，且已下载量计入进度`() {
        val pool = SlicePool(total = 1000L, slices = 4, gaps = listOf(100L..199L), initialCompleted = 900L)
        assertEquals(900L, pool.downloaded())
        val chunk = pool.take()
        assertNotNull(chunk)
        assertEquals(100L, chunk!!.start)
        assertEquals(199L, chunk.end)
        // 再没有别的活儿了
        assertEquals(null, pool.take())
    }

    @Test
    fun `账本 seed 后只差最后一段，补齐即完整`() {
        val ledger = WriteLedger(100L)
        ledger.seed(listOf(0L..49L))
        assertEquals(50L, ledger.coveredBytes)
        assertFalse(ledger.isComplete())

        ledger.record(50L, 99L)
        assertTrue(ledger.isComplete())
        assertEquals(100L, ledger.coveredBytes)
        // seed 不该被算成「重复写入」
        assertEquals(0, ledger.overlaps.get())
    }

    @Test
    fun `账本 snapshot 能原样还原续传所需区间`() {
        val ledger = WriteLedger(100L)
        ledger.seed(listOf(0L..9L, 20L..29L))
        assertEquals(listOf(listOf(0L, 9L), listOf(20L, 29L)), ledger.rangesSnapshot())
    }
}
