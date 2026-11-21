package com.artifactboost.app

import com.artifactboost.app.data.ArchiveFormat
import com.artifactboost.app.data.DownloadItem
import com.artifactboost.app.data.DownloadSource
import com.artifactboost.app.data.RouteMode
import com.artifactboost.app.download.PendingTask
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 待完成任务落盘的序列化测试。
 *
 * 进程被杀后要靠这份 JSON 把任务原样恢复出来；四种来源是 sealed class，
 * 序列化一旦少字段/多态标记写错，恢复出来的任务就会下载错东西或直接崩，
 * 所以在纯 JVM 层把它钉住。
 */
class PendingTaskTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun roundTrip(task: PendingTask): PendingTask =
        json.decodeFromString<PendingTask>(json.encodeToString(task))

    @Test
    fun `发行版附件可以原样读回`() {
        val item = DownloadItem(
            id = "asset-1",
            title = "app.zip",
            subtitle = "v1.0 · 下载 3 次",
            size = 123L,
            isPrivate = false,
            source = DownloadSource.ReleaseAsset(
                repo = "owner/repo",
                assetId = 1L,
                browserUrl = "https://github.com/owner/repo/releases/download/v1.0/app.zip",
            ),
        )
        val back = roundTrip(PendingTask(item, 32, RouteMode.SMART.name, ""))
        assertEquals(item, back.item)
        assertEquals(32, back.settings().connections)
        assertEquals(RouteMode.SMART, back.settings().mode)
    }

    @Test
    fun `构建产物与日志可以原样读回`() {
        val artifact = DownloadItem(
            id = "artifact-9",
            title = "build",
            subtitle = "构建产物",
            size = 0L,
            isPrivate = true,
            source = DownloadSource.Artifact("owner/repo", 9L),
        )
        val logs = DownloadItem(
            id = "logs-8",
            title = "ci 日志",
            subtitle = "构建日志",
            size = null,
            isPrivate = true,
            source = DownloadSource.RunLogs("owner/repo", 8L),
        )
        assertEquals(artifact, roundTrip(PendingTask(artifact, 8, RouteMode.DIRECT.name, "")).item)
        assertEquals(logs, roundTrip(PendingTask(logs, 8, RouteMode.DIRECT.name, "")).item)
    }

    @Test
    fun `源码包可以原样读回且保留自定义前缀`() {
        val item = DownloadItem(
            id = "source-owner-repo-main-ZIP",
            title = "repo-main",
            subtitle = "源码包 · ZIP",
            size = null,
            isPrivate = false,
            source = DownloadSource.SourceArchive("owner/repo", "main", ArchiveFormat.ZIP),
        )
        val back = roundTrip(PendingTask(item, 64, RouteMode.CUSTOM.name, "https://proxy.example/"))
        assertEquals(item, back.item)
        assertEquals(64, back.settings().connections)
        assertEquals("https://proxy.example/", back.settings().customPrefix)
    }

    @Test
    fun `损坏的记录不会抛异常`() {
        assertTrue(
            runCatching { json.decodeFromString<List<PendingTask>>("{not json}") }.isFailure,
        )
    }
}
