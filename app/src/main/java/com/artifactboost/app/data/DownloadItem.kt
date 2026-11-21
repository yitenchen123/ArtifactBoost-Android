package com.artifactboost.app.data

import com.artifactboost.app.util.formatTimestamp
import kotlinx.serialization.Serializable

@Serializable
enum class ArchiveFormat(
    val path: String,
    /** codeload.github.com 用的路径段（源码包直连，不再经过 api.github.com） */
    val codeloadPath: String,
    val fileExtension: String,
    val title: String,
) {
    ZIP("zipball", "zip", "zip", "ZIP"),
    TARBALL("tarball", "tar.gz", "tar.gz", "TAR.GZ"),
}

/** 能加速下载的东西：构建产物 / 构建日志 / 发行版附件 / 源码包 */
@Serializable
sealed class DownloadSource {
    abstract val repo: String

    @Serializable
    data class Artifact(override val repo: String, val id: Long) : DownloadSource()

    @Serializable
    data class RunLogs(override val repo: String, val runId: Long) : DownloadSource()

    @Serializable
    data class ReleaseAsset(
        override val repo: String,
        val assetId: Long,
        /**
         * 该附件在 github.com 上的**稳定公开地址**
         * （`https://github.com/{owner}/{repo}/releases/download/{tag}/{name}`）。
         *
         * 之所以要单独存一份：镜像 ghfast.top 只认 `github.com` 域名，
         * 而 [GitHubClient.resolveDownloadUrl] 拿到的是 302 之后的 Azure 签名地址，
         * 那个地址套不进 ghfast。有了这个稳定地址，发行版就能走 ghfast 加速。
         * 老数据 / 拿不到 tag 时为 null，此时退回原行为（只用签名地址 + 常规镜像）。
         */
        val browserUrl: String? = null,
    ) : DownloadSource()

    @Serializable
    data class SourceArchive(
        override val repo: String,
        val ref: String,
        val format: ArchiveFormat,
    ) : DownloadSource()

    /** 源码包由 GitHub 现场打包，不支持 Range 分段，只能单连接下载 */
    val supportsChunkedDownload: Boolean
        get() = this !is SourceArchive

    val iconName: String
        get() = when (this) {
            is Artifact -> "archive"
            is RunLogs -> "description"
            is ReleaseAsset -> "inventory_2"
            is SourceArchive -> "code"
        }

    val kindName: String
        get() = when (this) {
            is Artifact -> "构建产物"
            is RunLogs -> "构建日志"
            is ReleaseAsset -> "发行版附件"
            is SourceArchive -> "源码包"
        }

    /**
     * 能否走 ghfast 这类**只认 github.com 原始地址**的镜像。
     * 目前只有发行版附件有这个稳定地址（构建产物/日志的地址是临时的）。
     */
    val ghfastEligibleUrl: String?
        get() = (this as? ReleaseAsset)?.browserUrl?.takeIf { it.isNotBlank() }
}

/** 界面上一行「可下载项」 */
@Serializable
data class DownloadItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val size: Long?,
    val isPrivate: Boolean,
    val source: DownloadSource,
) {
    val iconName: String get() = source.iconName
    val kindName: String get() = source.kindName

    /** 落盘文件名 */
    val fileName: String
        get() = when (val s = source) {
            is DownloadSource.Artifact -> sanitize(title) + ".zip"
            is DownloadSource.RunLogs -> sanitize(title) + "-logs.zip"
            is DownloadSource.ReleaseAsset -> sanitize(title)
            is DownloadSource.SourceArchive -> {
                val base = if (s.ref.isEmpty()) "source" else sanitize(s.ref)
                "${sanitize(title)}-$base.${s.format.fileExtension}"
            }
        }

    private fun sanitize(raw: String): String =
        raw.replace('/', '_').replace(':', '_').trim()
}

// MARK: - 由接口数据构造下载项

fun artifactItem(artifact: GHArtifact, repo: GHRepo): DownloadItem = DownloadItem(
    id = "artifact-${artifact.id}",
    title = artifact.name,
    subtitle = "构建产物 · ${formatTimestamp(artifact.createdAt) ?: "时间未知"}",
    size = artifact.sizeInBytes,
    isPrivate = repo.isPrivate,
    source = DownloadSource.Artifact(repo.fullName, artifact.id),
)

fun runLogsItem(run: GHWorkflowRun, repo: GHRepo): DownloadItem = DownloadItem(
    id = "logs-${run.id}",
    title = "${run.name ?: "Workflow"} #${run.runNumber} 日志",
    subtitle = "构建日志 · ${run.headBranch ?: "-"}",
    size = null,
    isPrivate = repo.isPrivate,
    source = DownloadSource.RunLogs(repo.fullName, run.id),
)

fun releaseAssetItem(asset: GHReleaseAsset, release: GHRelease, repo: GHRepo): DownloadItem = DownloadItem(
    id = "asset-${asset.id}",
    title = asset.name,
    subtitle = "${release.displayName} · 下载 ${asset.downloadCount} 次",
    size = asset.size,
    isPrivate = repo.isPrivate,
    source = DownloadSource.ReleaseAsset(
        repo = repo.fullName,
        assetId = asset.id,
        // 拼出 github.com 上的稳定下载地址，供 ghfast 这类镜像使用。
        // 附件名可能含空格/中文，这里按路径段做一次编码。
        browserUrl = "https://github.com/${repo.fullName}/releases/download/"
            + "${urlEncodeSegment(release.tagName)}/${urlEncodeSegment(asset.name)}",
    ),
)

fun sourceArchiveItem(repo: GHRepo, ref: String, format: ArchiveFormat): DownloadItem {
    val label = ref.ifEmpty { repo.defaultBranch ?: "默认分支" }
    return DownloadItem(
        id = "source-${repo.fullName}-$label-${format.name}",
        title = "${repo.name}-$label",
        subtitle = "源码包 · ${format.title}",
        size = null,
        isPrivate = repo.isPrivate,
        source = DownloadSource.SourceArchive(repo.fullName, ref, format),
    )
}
