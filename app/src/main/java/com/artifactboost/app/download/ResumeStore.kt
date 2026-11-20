package com.artifactboost.app.download

import com.artifactboost.app.ArtifactBoostApp
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 断点续传的元数据：记录「这个文件已经下过哪些字节区间」。
 *
 * 老实现只在**进程内**续做（分片队列活着的时候），App 一被杀掉、
 * 或者用户点了取消，已下好的几百 MB 就全废了 —— 下次只能从 0 开始。
 * 这份元数据配合半成品文件，让下载可以跨进程续上。
 */
@Serializable
data class ResumeMeta(
    /** 文件总字节数：体积一变（比如附件被重新上传）就不能续了 */
    val total: Long,
    val etag: String? = null,
    val lastModified: String? = null,
    /** 已覆盖的字节区间，每项是 [start, end] 闭区间 */
    val ranges: List<List<Long>> = emptyList(),
    val updatedAt: Long = 0L,
) {
    val coveredBytes: Long
        get() = ranges.sumOf { pair ->
            if (pair.size == 2 && pair[1] >= pair[0]) pair[1] - pair[0] + 1 else 0L
        }
}

/** 引擎内部使用的续传上下文 */
class ResumeContext(
    val key: String,
    val partFile: File,
    val etag: String?,
    val lastModified: String?,
    /** 上次已经下好的区间（闭区间） */
    val covered: List<LongRange>,
) {
    val coveredBytes: Long get() = covered.sumOf { it.last - it.first + 1 }
}

/**
 * 断点续传的落盘仓库。
 *
 * 放 `filesDir/partial`（应用私有、系统不会像 cache 那样随手清理）。
 * 半成品与元数据成对出现（`<key>.part` + `<key>.json`），
 * 任何一方缺失或校验不过就整对丢弃，绝不拿错文件拼接。
 */
object ResumeStore {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val root: File by lazy {
        // 放外部私有目录：半成品动辄几百 MB，塞内部存储容易把低端机撑爆；
        // 与成品目录同一文件系统，完成时 renameTo 是原子操作。
        val base = ArtifactBoostApp.instance.getExternalFilesDir(null)
            ?: ArtifactBoostApp.instance.filesDir
        File(base, "partial").also { it.mkdirs() }
    }

    fun partFile(key: String): File = File(root, "${stem(key)}.part")

    fun metaFile(key: String): File = File(root, "${stem(key)}.json")

    fun load(key: String): ResumeMeta? = runCatching {
        val file = metaFile(key)
        if (!file.exists()) return null
        json.decodeFromString<ResumeMeta>(file.readText())
    }.getOrNull()

    /**
     * 判断能不能续上；能续就返回上下文，不能续会把残留的半成品清掉。
     *
     * 三道校验缺一不可：
     *  1. 体积一致 —— 文件被替换时体积几乎一定会变；
     *  2. 半成品文件在，且大小等于总长；
     *  3. ETag / Last-Modified 一致（两边都有时才比）。
     */
    fun prepare(key: String, total: Long, etag: String?, lastModified: String?): ResumeContext? {
        val meta = load(key)
        if (meta == null) {
            partFile(key).delete()
            return null
        }
        val part = partFile(key)
        if (meta.total != total || !part.exists() || part.length() != total) {
            discard(key)
            return null
        }
        if (!meta.etag.isNullOrEmpty() && !etag.isNullOrEmpty() && meta.etag != etag) {
            discard(key)
            return null
        }
        val covered = meta.ranges.mapNotNull { pair ->
            if (pair.size == 2 && pair[0] >= 0 && pair[1] >= pair[0] && pair[1] < total) {
                pair[0]..pair[1]
            } else {
                null
            }
        }
        if (covered.isEmpty()) {
            discard(key)
            return null
        }
        return ResumeContext(
            key = key,
            partFile = part,
            etag = etag ?: meta.etag,
            lastModified = lastModified ?: meta.lastModified,
            covered = covered,
        )
    }

    /** 原子落盘：先写临时文件再改名，进程在写入途中被杀也不会留下半截 JSON */
    fun save(key: String, meta: ResumeMeta) {
        runCatching {
            val tmp = File(root, "${stem(key)}.json.tmp")
            tmp.writeText(json.encodeToString(meta))
            tmp.renameTo(metaFile(key))
        }
    }

    /** 丢弃该任务的全部续传数据（用户主动取消 / 下载完成） */
    fun discard(key: String) {
        runCatching { partFile(key).delete() }
        runCatching { metaFile(key).delete() }
    }

    /** 清理过期残留（默认 7 天），防止中途放弃的任务永久占着磁盘 */
    fun prune(maxAgeMillis: Long = 7L * 24 * 3600 * 1000) {
        val cutoff = System.currentTimeMillis() - maxAgeMillis
        root.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    /** 把下载项 id 变成安全的文件名：加稳定哈希后缀，避免含斜杠的 key 撞名 */
    private fun stem(key: String): String {
        var hash = -0x340d631b7bdddcdbL
        for (byte in key.toByteArray()) {
            hash = hash xor (byte.toLong() and 0xff)
            hash *= 0x100000001b3L
        }
        val readable = key.map { ch ->
            if (ch.isLetterOrDigit() || ch == '-' || ch == '.') ch else '_'
        }.joinToString("").take(48)
        return "$readable-${hash.toULong().toString(16)}"
    }
}
