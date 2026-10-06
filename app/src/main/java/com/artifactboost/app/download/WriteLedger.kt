package com.artifactboost.app.download

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 写入覆盖账本：记录「哪些字节区间已经被真正写进文件」。
 *
 * ## 为什么需要它
 *
 * 引擎原来的完整性校验只有一句 `written.get() != total` —— 那只是**总量**校验。
 * 「重复写了两段 + 漏写了一段」的总量完全可能相等，最后文件大小对得上、
 * 内容却是错的 —— 症状正是「能下完但解压不了」。
 *
 * 这个账本把校验升级成**覆盖**校验：
 *  - 每次成功写盘都登记区间；
 *  - 任何一次「重叠写入」立刻发现（说明同一段被两个 worker 写了）；
 *  - 收尾时核对「已覆盖字节 == 文件总大小」且「区间无重叠」，
 *    两者同时成立才说明每个字节都被恰好写过一次。
 *
 * 开销：每写一片登记一次（几十字节的节点 + 一把锁），
 * 相对于一片的网络与磁盘开销完全可以忽略。
 */
internal class WriteLedger(private val total: Long) {

    private val lock = Any()

    /** 已写入的区间，按起点有序，且保证两两不相交、不相邻 */
    private val ranges = ArrayList<LongRange>(64)

    private val covered = AtomicLong(0)

    /** 检测到的重叠写入次数（> 0 说明调度出问题了） */
    val overlaps = AtomicInteger(0)

    /** 已覆盖的字节数 */
    val coveredBytes: Long get() = covered.get()

    /** 区间个数（诊断用） */
    val segmentCount: Int get() = synchronized(lock) { ranges.size }

    /**
     * 登记一次写盘。
     *
     * @return 真正新覆盖的字节数。若该区间已被完全写过，返回 0；
     *         若与已有区间部分重叠，只把新增部分计入 covered。
     */
    fun record(start: Long, endInclusive: Long): Long {
        if (endInclusive < start) return 0
        synchronized(lock) {
            val end = endInclusive
            var newBytes = 0L

            // 找到第一个可能与新区间重叠/相邻的位置
            var index = ranges.binarySearchFirstAtOrAfter(start)

            // 与前面的区间比较：如果前一个区间与新区间重叠或相邻，合并
            if (index > 0) {
                val prev = ranges[index - 1]
                if (prev.last >= start - 1) {
                    index -= 1
                }
            }

            var mergedStart = start
            var mergedEnd = end
            var overlapsFound = 0

            // 向后吞掉所有与新区间相交或相邻的区间
            var i = index
            while (i < ranges.size) {
                val r = ranges[i]
                if (r.first > mergedEnd + 1) break
                // 正式判定重叠：两个区间真正有交集（不是仅相邻）
                if (r.first <= mergedEnd && r.last >= mergedStart) overlapsFound++
                if (r.first < mergedStart) mergedStart = r.first
                if (r.last > mergedEnd) mergedEnd = r.last
                i++
            }

            if (overlapsFound > 0) overlaps.addAndGet(overlapsFound)

            // 新覆盖的字节 = 合并后的长度 − 被吞掉的旧区间总长
            var absorbed = 0L
            for (j in index until i) {
                absorbed += ranges[j].last - ranges[j].first + 1
            }
            newBytes = (mergedEnd - mergedStart + 1) - absorbed
            if (newBytes < 0) newBytes = 0

            // 用合并结果替换被吞掉的那些区间
            repeat(i - index) { ranges.removeAt(index) }
            ranges.add(index, mergedStart..mergedEnd)

            covered.addAndGet(newBytes)
            return newBytes
        }
    }

    /** 是否每个字节都恰好覆盖了一次（区间不重叠 + 覆盖量等于总大小） */
    fun isComplete(): Boolean = synchronized(lock) {
        if (overlaps.get() > 0) return false
        if (ranges.size != 1) return false
        val only = ranges[0]
        return only.first == 0L && only.last == total - 1
    }

    /** 尚未覆盖的区间列表（诊断 / 补漏用），最多返回 [limit] 段 */
    fun gaps(limit: Int = 50): List<LongRange> {
        synchronized(lock) {
            val result = ArrayList<LongRange>()
            var cursor = 0L
            for (r in ranges) {
                if (r.first > cursor) {
                    result.add(cursor until r.first)
                    if (result.size >= limit) return result
                }
                cursor = maxOf(cursor, r.last + 1)
            }
            if (cursor < total) result.add(cursor until total)
            return result
        }
    }

    /** 生成一段人类可读的差异描述（校验失败时写进错误信息） */
    fun describe(): String = synchronized(lock) {
        val miss = total - covered.get()
        buildString {
            append("已覆盖 ${covered.get()}/$total 字节，缺 $miss 字节")
            if (overlaps.get() > 0) append("，检测到 ${overlaps.get()} 次重复写入")
            val g = gaps(limit = 3)
            if (g.isNotEmpty()) {
                append("，缺口示例：")
                append(g.joinToString("、") { "[${it.first}-${it.last}]" })
            }
        }
    }

    /** 在有序区间表里找第一个起点 >= value 的下标 */
    private fun ArrayList<LongRange>.binarySearchFirstAtOrAfter(value: Long): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (this[mid].first < value) lo = mid + 1 else hi = mid
        }
        return lo
    }
}
