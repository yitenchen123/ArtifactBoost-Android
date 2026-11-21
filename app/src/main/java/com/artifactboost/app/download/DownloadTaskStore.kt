package com.artifactboost.app.download

import android.content.Context
import com.artifactboost.app.data.AccelerationSettings
import com.artifactboost.app.data.DownloadItem
import com.artifactboost.app.data.RouteMode
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 未完成任务的持久化记录（下载项 + 加速设置快照）。
 *
 * 进程被系统杀掉后内存队列全丢；有了这份记录，
 * 下次启动时 [DownloadManager.restorePending] 能把没下完的任务自动续上。
 * 只有「死时还没终结」的任务会留在这里：
 * 完成 / 失败 / 用户取消 / 移除都会删记录，不会复活。
 */
@Serializable
data class PendingTask(
    val item: DownloadItem,
    val connections: Int,
    val mode: String,
    val customPrefix: String,
) {
    fun settings(): AccelerationSettings = AccelerationSettings(
        connections = connections,
        mode = runCatching { RouteMode.valueOf(mode) }.getOrDefault(RouteMode.SMART),
        customPrefix = customPrefix,
    )
}

/**
 * 任务落盘：`filesDir/pending-downloads.json`。
 *
 * 读写都在 [lock] 内完成，调用方统一放到 IO 线程；
 * 文件很小（一条任务几百字节），全量重写足够快，也省掉了数据库依赖。
 */
class DownloadTaskStore(context: Context) {

    private val file = File(context.filesDir, "pending-downloads.json")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val lock = Any()

    fun loadAll(): List<PendingTask> = synchronized(lock) { read() }

    fun save(item: DownloadItem, settings: AccelerationSettings) {
        synchronized(lock) {
            val rest = read().filterNot { it.item.id == item.id }
            write(
                rest + PendingTask(
                    item = item,
                    connections = settings.clampedConnections,
                    mode = settings.mode.name,
                    customPrefix = settings.customPrefix,
                ),
            )
        }
    }

    fun remove(id: String) {
        synchronized(lock) {
            val all = read()
            val next = all.filterNot { it.item.id == id }
            if (next.size != all.size) write(next)
        }
    }

    /** 读不出来（升级字段变更 / 文件损坏）就当没有，顺手清掉，不让坏数据卡死启动 */
    private fun read(): List<PendingTask> {
        if (!file.exists()) return emptyList()
        return runCatching { json.decodeFromString<List<PendingTask>>(file.readText()) }
            .getOrElse {
                runCatching { file.delete() }
                emptyList()
            }
    }

    private fun write(tasks: List<PendingTask>) {
        runCatching {
            file.parentFile?.mkdirs()
            // 先写临时文件再整体覆盖：写到一半被杀也不会留下半个 JSON
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(tasks))
            if (file.exists() && !file.delete()) {
                tmp.delete()
                return
            }
            if (!tmp.renameTo(file)) tmp.copyTo(file, overwrite = true)
            if (tmp.exists()) tmp.delete()
        }
    }
}
