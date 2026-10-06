package com.artifactboost.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** GitHub 接口错误，文案对齐 iOS 版的用户提示。 */
sealed class GitHubException(message: String) : IOException(message) {
    class BadUrl : GitHubException("无效的请求地址")
    class BadResponse : GitHubException("服务器响应异常")
    class Http(val code: Int, val detail: String) : GitHubException(buildMessage(code, detail))
    object ArtifactExpired : GitHubException("该产物已过期，GitHub 已将其删除")
    object DownloadUrlNotFound : GitHubException("未能获取产物下载地址")

    private companion object {
        fun buildMessage(code: Int, detail: String): String = when (code) {
            401 -> "Token 无效或已过期（401），请重新登录"
            403 -> "权限不足或触发限流（403）$detail\n" +
                "如果是别人的公开仓库：fine-grained Token 需要勾选 Public Repositories 只读；" +
                "classic Token 勾了 repo 即可。"
            404 -> "未找到（404）：仓库不存在、是私有仓库，或你的 Token 没有被授权访问它。"
            else -> "请求失败（$code）$detail"
        }
    }
}

/** 搜索排序方式 */
enum class RepoSort(val title: String) {
    BEST_MATCH("最佳匹配"),
    STARS("星标最多"),
    UPDATED("最近更新"),
}

/**
 * GitHub REST API 客户端。
 *
 * 关键点：GitHub 对下载类接口都会 **302 跳转** 到一个临时的签名地址
 * （产物/日志在 Azure Blob，源码包在 codeload）。这里必须拦下跳转、
 * 取出真实地址，后续分段下载直接打这个地址——既不再需要 Token，
 * 也不再经过 api.github.com。
 */
class GitHubClient(val token: String) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /** 普通 API 调用：跟随跳转即可 */
    private val apiClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // 单次调用总封顶：readTimeout 按每次 read 空闲计时，慢速 trickle 下能拖很久，
        // callTimeout 保证一次请求最多 45s 必返回（成功/超时），不再无限挂起。
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    /**
     * 解析签名地址专用：**禁止自动跟随跳转**。
     * 只有拦下 302 才拿得到 Azure/codeload 的真实地址。
     */
    private val redirectClient: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // 解析阶段永远直连 api.github.com，国内抖动大，单次 30s 封顶，
        // 配合 performDownload 的限时+重试，最多约 60s 必有结果（成功/中文报错）。
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    // MARK: - 请求构造

    private fun makeUrl(path: String, query: Map<String, String?> = emptyMap()): String {
        val builder = ("https://api.github.com/$path").toHttpUrlOrNull()?.newBuilder()
            ?: throw GitHubException.BadUrl()
        query.forEach { (key, value) -> if (value != null) builder.addQueryParameter(key, value) }
        return builder.build().toString()
    }

    private fun authorizedRequest(url: String): Request.Builder = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer $token")
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", USER_AGENT)

    private suspend fun getRaw(
        path: String,
        query: Map<String, String?> = emptyMap(),
        accept: String? = null,
    ): Pair<String, okhttp3.Response> = withContext(Dispatchers.IO) {
        val builder = authorizedRequest(makeUrl(path, query))
        if (accept != null) builder.header("Accept", accept)
        apiClient.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                if (response.code == 410) throw GitHubException.ArtifactExpired
                throw GitHubException.Http(response.code, errorMessage(body))
            }
            body to response
        }
    }

    private inline fun <reified T> decode(body: String): T =
        json.decodeFromString(body)

    private suspend inline fun <reified T> get(
        path: String,
        query: Map<String, String?> = emptyMap(),
    ): T = decode(getRaw(path, query).first)

    /** 从 GitHub 的错误响应里抠出 message 字段 */
    private fun errorMessage(body: String): String = try {
        (json.parseToJsonElement(body) as? JsonObject)
            ?.get("message")?.jsonPrimitive?.content.orEmpty()
    } catch (_: Exception) {
        ""
    }

    // MARK: - 接口

    /** 验证 Token 并返回当前用户 */
    suspend fun validateToken(): GHUser = get("user")

    /** 当前用户可见的仓库（自己 + 协作 + 组织） */
    suspend fun repos(page: Int): List<GHRepo> = get(
        "user/repos",
        mapOf(
            "per_page" to "100",
            "page" to page.toString(),
            "sort" to "updated",
            "affiliation" to "owner,collaborator,organization_member",
        ),
    )

    /**
     * 搜索全站仓库：不限于自己的仓库，
     * 别人的公开仓库也能搜到并下载。
     */
    suspend fun searchRepos(keyword: String, sort: RepoSort = RepoSort.BEST_MATCH): List<GHRepo> {
        val query = mutableMapOf("q" to keyword, "per_page" to "40")
        when (sort) {
            RepoSort.BEST_MATCH -> Unit
            RepoSort.STARS -> {
                query["sort"] = "stars"
                query["order"] = "desc"
            }
            RepoSort.UPDATED -> {
                query["sort"] = "updated"
                query["order"] = "desc"
            }
        }
        return get<RepoSearchResponse>("search/repositories", query).items
    }

    /** 按 owner/repo 取单个仓库（「直接打开仓库」用） */
    suspend fun repo(fullName: String): GHRepo = get("repos/$fullName")

    /** 仓库最近的 workflow 运行记录 */
    suspend fun workflowRuns(repo: GHRepo): List<GHWorkflowRun> =
        get<RunsResponse>("repos/${repo.fullName}/actions/runs", mapOf("per_page" to "30"))
            .workflowRuns

    /** 取单次 workflow 运行（「直接打开 Actions 链接」用） */
    suspend fun workflowRun(fullName: String, runId: Long): GHWorkflowRun =
        get("repos/$fullName/actions/runs/$runId")

    /** 某次运行产生的产物列表 */
    suspend fun artifacts(repo: GHRepo, run: GHWorkflowRun): List<GHArtifact> =
        get<ArtifactsResponse>(
            "repos/${repo.fullName}/actions/runs/${run.id}/artifacts",
            mapOf("per_page" to "100"),
        ).artifacts

    /** 某个仓库的发行版（Release） */
    suspend fun releases(repo: GHRepo): List<GHRelease> =
        get("repos/${repo.fullName}/releases", mapOf("per_page" to "50"))

    /** 仓库分支（用于下载任意分支的源码包） */
    suspend fun branches(repo: GHRepo): List<GHBranch> =
        get("repos/${repo.fullName}/branches", mapOf("per_page" to "100"))

    /**
     * 解析任意下载项的签名地址。
     *
     * GitHub 对这些接口都会 302 跳转到带签名的真实地址，这里拦下跳转拿真实地址，
     * 后续分段下载直接打这个地址（不再需要 Token，也不再经过 api.github.com）。
     *
     * 提速要点：
     *  1. 先用 **HEAD** 拿跳转（不产生正文传输），失败再退回 GET；
     *  2. `followRedirects = false` + 拿到 3xx 就立刻结束 —— 产物是几百 MB 的资源，
     *     走完整个响应体纯属浪费，302 一出现 Location 就有了。
     */
    suspend fun resolveDownloadUrl(source: DownloadSource): String = withContext(Dispatchers.IO) {
        val extraHeaders = mutableMapOf<String, String>()
        val path = when (source) {
            is DownloadSource.Artifact -> "repos/${source.repo}/actions/artifacts/${source.id}/zip"
            is DownloadSource.RunLogs -> "repos/${source.repo}/actions/runs/${source.runId}/logs"
            is DownloadSource.ReleaseAsset -> {
                // 附件接口默认返回 JSON 元数据，必须显式要二进制才会 302
                extraHeaders["Accept"] = "application/octet-stream"
                "repos/${source.repo}/releases/assets/${source.assetId}"
            }
            is DownloadSource.SourceArchive -> {
                val encoded = encodePathSegment(source.ref)
                val archivePath = source.format.path
                if (encoded.isEmpty()) "repos/${source.repo}/$archivePath"
                else "repos/${source.repo}/$archivePath/$encoded"
            }
        }

        val url = makeUrl(path)
        var lastError: Throwable = GitHubException.DownloadUrlNotFound

        // 先试 HEAD（最快，不产生正文传输），拿不到 302 再退回 GET
        for ((index, method) in listOf("HEAD", "GET").withIndex()) {
            val builder = authorizedRequest(url)
            builder.method(method, null)
            extraHeaders.forEach { (key, value) -> builder.header(key, value) }

            try {
                redirectClient.newCall(builder.build()).execute().use { response ->
                    if (response.code == 410) throw GitHubException.ArtifactExpired
                    // 302/303 带着 Location，就是我们要的签名地址
                    if (response.code == 302 || response.code == 303) {
                        val location = response.header("Location")
                        if (!location.isNullOrBlank()) return@withContext location
                    }
                    if (!response.isSuccessful) {
                        val code = response.code
                        throw GitHubException.Http(code, errorMessage(response.body?.string().orEmpty()))
                    }
                    // 2xx 但没跳转：HEAD 不被支持，换 GET 再试一次
                    lastError = GitHubException.DownloadUrlNotFound
                }
            } catch (e: GitHubException.ArtifactExpired) {
                throw e // 明确结论，不做无谓的第二次请求
            } catch (e: GitHubException.Http) {
                // 鉴权/权限类错误直接抛出；其余留给下一次尝试
                if (e.code == 401 || e.code == 403 || e.code == 404) throw e
                lastError = e
            } catch (e: Exception) {
                lastError = e
            }
            if (index == 1) break
        }
        throw lastError
    }

    /**
     * 取仓库 README 的 Markdown 原文。
     * `?ref=` 跟随当前选中的分支；`Accept: application/vnd.github.raw` 直接返回纯文本。
     * 404 表示没有 README，返回 null 而不是抛错。
     */
    suspend fun readme(repo: GHRepo, ref: String? = null): GHReadme? = withContext(Dispatchers.IO) {
        val target = ref?.takeIf { it.isNotEmpty() } ?: repo.defaultBranch.orEmpty()
        val query = if (target.isNotEmpty()) mapOf("ref" to target) else emptyMap()

        val request = authorizedRequest(makeUrl("repos/${repo.fullName}/readme", query))
            .header("Accept", "application/vnd.github.raw")
            .build()

        apiClient.newCall(request).execute().use { response ->
            if (response.code == 404) return@withContext null
            if (!response.isSuccessful) {
                throw GitHubException.Http(response.code, errorMessage(response.body?.string().orEmpty()))
            }
            val text = response.body?.string().orEmpty()
            if (text.isEmpty()) return@withContext null
            // README 接口在 Content-Location 里带了文件路径，取末段做标题
            val path = response.header("Content-Location")
                ?.substringAfterLast('/')
                ?.takeIf { it.isNotBlank() }
                ?: "README.md"
            GHReadme(path = path, text = text)
        }
    }

    /**
     * 默认分支上的提交数。
     * GitHub 用 `Link` 头的 last 页号给出，per_page=1 只需一次请求；
     * 拿不到就返回 null，界面自动隐藏这一项。
     */
    suspend fun commitCount(repo: GHRepo): Int? = withContext(Dispatchers.IO) {
        val request = authorizedRequest(
            makeUrl(
                "repos/${repo.fullName}/commits",
                mapOf("sha" to (repo.defaultBranch ?: "HEAD"), "per_page" to "1"),
            ),
        ).build()

        apiClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val link = response.header("Link") ?: return@withContext null
            lastPageNumber(link)
        }
    }

    /** 从 `Link: <...?page=42>; rel="last"` 里抠出 42 */
    private fun lastPageNumber(linkHeader: String): Int? {
        for (segment in linkHeader.split(',')) {
            if (!segment.contains("rel=\"last\"")) continue
            val marker = segment.indexOf("page=")
            if (marker < 0) continue
            val digits = segment.substring(marker + "page=".length).takeWhile { it.isDigit() }
            if (digits.isNotEmpty()) return digits.toIntOrNull()
        }
        return null
    }

    /** 路径段百分号编码：`feature/foo` 里的 `/` 要保留为路径分隔符 */
    private fun encodePathSegment(raw: String): String =
        raw.split('/').joinToString("/") { segment ->
            java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }

    companion object {
        const val USER_AGENT = "ArtifactBoost-Android"
    }
}
