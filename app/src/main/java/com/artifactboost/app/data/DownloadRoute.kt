package com.artifactboost.app.data

import android.content.Context
import android.content.SharedPreferences
import java.net.URLEncoder

/**
 * 通道的作用域：决定它能套在哪种 URL 上。
 *
 * 这是新增 ghfast 后必须区分的一件事 ——
 * 常规镜像（gh-proxy 等）是「把已签名的真实地址塞进前缀」，任何地址都能中转；
 * 而 ghfast.top 只认 `github.com` 原始地址，套到 Azure 签名地址上会直接 400。
 */
enum class RouteScope {
    /** 可用于任何地址（含 Azure 签名地址） */
    ANY,

    /** 只能用于 github.com 的原始地址（发行版附件的稳定下载链接） */
    GITHUB_ONLY,
}

/**
 * 下载通道：直连 Azure 签名地址，或经由镜像 / 自建反代中转（前缀 + 原始地址）。
 */
data class DownloadRoute(
    val name: String,
    val prefix: String,
    val scope: RouteScope = RouteScope.ANY,
) {
    val isDirect: Boolean get() = prefix.isEmpty()

    /** 把通道前缀套到已签名的产物地址上 */
    fun apply(signedUrl: String): String =
        if (prefix.isEmpty()) signedUrl else prefix + signedUrl

    companion object {
        val DIRECT = DownloadRoute("直连", "")

        /**
         * 内置公共镜像。它们只是中转「已签名的产物地址」，不接触 Token；
         * 但私有仓库的产物不应经过第三方，所以只在公开仓库且开启智能加速时使用。
         * 不同节点往往落在不同机房/线路，多通道并行时带宽可以叠加。
         */
        val BUILT_IN_MIRRORS = listOf(
            DownloadRoute("gh-proxy.com", "https://gh-proxy.com/"),
            DownloadRoute("slink.ltd", "https://slink.ltd/"),
            DownloadRoute("hk.gh-proxy.com", "https://hk.gh-proxy.com/"),
            DownloadRoute("moeyy.xyz", "https://github.moeyy.xyz/"),
        )

        /**
         * ghfast.top —— **只能用于发行版**。
         *
         * 它的用法是 `https://ghfast.top/https://github.com/...`，
         * 也就是必须给它一个 github.com 的原始地址；
         * 构建产物 / 构建日志解析出来的是临时签名地址，套上去会被拒。
         * 因此单独归类，只在下载发行版附件时参与候选。
         */
        val GHFAST = DownloadRoute(
            name = "ghfast.top",
            prefix = "https://ghfast.top/",
            scope = RouteScope.GITHUB_ONLY,
        )

        /**
         * 给一次具体下载挑可用的镜像。
         *
         * @param githubUrl 该下载在 github.com 上的稳定地址；只有发行版有，其余为 null。
         *        为 null 时 [RouteScope.GITHUB_ONLY] 的通道会被剔除。
         */
        fun mirrorsFor(githubUrl: String?): List<DownloadRoute> =
            if (githubUrl.isNullOrBlank()) BUILT_IN_MIRRORS else BUILT_IN_MIRRORS + GHFAST

        /** 补全并校验用户填的前缀，非法时返回空串 */
        fun normalizedPrefix(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return ""
            if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return ""
            return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
        }
    }
}

/** 带权重的通道，用于按权重分配分块；初始权重均等，下载中按实时吞吐动态调整 */
data class ScoredRoute(
    val route: DownloadRoute,
    val speed: Double,
)

enum class RouteMode(val title: String, val detail: String) {
    DIRECT("直连", "直接连 GitHub 存储，最安全，但国内通常很慢"),
    SMART("智能加速", "直连与公共镜像多通道并行，带宽叠加"),
    CUSTOM("自定义", "使用你自己搭建的中转（Cloudflare Worker / 反向代理）"),
}

/**
 * 持久化的加速设置：在「设置」页调好并保存，下载时直接套用。
 * 对应 iOS 版的 `AccelerationSettings`（UserDefaults → SharedPreferences）。
 */
data class AccelerationSettings(
    val connections: Int = 16,
    val mode: RouteMode = RouteMode.SMART,
    val customPrefix: String = "",
) {
    val clampedConnections: Int get() = connections.coerceIn(1, MAX_CONNECTIONS)

    /**
     * 当前设置下的候选通道（直连永远保留兜底）。
     * 私有仓库一律只走直连，避免产物数据经过第三方。
     *
     * @param githubUrl 该下载在 github.com 上的稳定地址；只有发行版有。
     *        非空时 ghfast 才会进入候选（它只认 github.com 原始地址）。
     */
    fun candidateRoutes(isPrivateRepo: Boolean, githubUrl: String? = null): List<DownloadRoute> = when (mode) {
        RouteMode.DIRECT -> listOf(DownloadRoute.DIRECT)

        RouteMode.CUSTOM -> {
            val prefix = DownloadRoute.normalizedPrefix(customPrefix)
            if (prefix.isEmpty()) listOf(DownloadRoute.DIRECT)
            else listOf(DownloadRoute("自定义加速", prefix), DownloadRoute.DIRECT)
        }

        RouteMode.SMART ->
            if (isPrivateRepo) listOf(DownloadRoute.DIRECT)
            else listOf(DownloadRoute.DIRECT) + DownloadRoute.mirrorsFor(githubUrl)
    }

    // MARK: - 持久化

    fun save(context: Context) {
        val store = prefs(context)
        store.edit()
            .putInt(KEY_CONNECTIONS, clampedConnections)
            .putString(KEY_MODE, mode.name)
            .putString(KEY_CUSTOM_PREFIX, customPrefix)
            .apply()
    }

    companion object {
        /** 引擎接受的并发上限。128 属于极限档：吃千兆内网/高速 Wi-Fi 用，
         *  普通宽带吃不满，且更容易被 CDN 限流（引擎会自动退让，不会失败）。 */
        const val MAX_CONNECTIONS = 128

        /** 设置页档位 */
        val CONNECTION_OPTIONS = listOf(8, 16, 32, 64, 128)

        private const val PREFS_NAME = "artifactboost_settings"
        private const val KEY_CONNECTIONS = "ab.connections"
        private const val KEY_MODE = "ab.routeMode"
        private const val KEY_CUSTOM_PREFIX = "ab.customPrefix"

        private fun prefs(context: Context): SharedPreferences =
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        fun load(context: Context): AccelerationSettings {
            val store = prefs(context)
            val storedConnections = store.getInt(KEY_CONNECTIONS, 0)
            val mode = runCatching {
                RouteMode.valueOf(store.getString(KEY_MODE, null) ?: RouteMode.SMART.name)
            }.getOrDefault(RouteMode.SMART)

            return AccelerationSettings(
                connections = if (storedConnections > 0) storedConnections else 16,
                mode = mode,
                customPrefix = store.getString(KEY_CUSTOM_PREFIX, "").orEmpty(),
            )
        }
    }
}

/** 估算给定字符串的 URL 编码长度（保留 `/`），用于拼接 codeload 路径 */
internal fun urlEncodeSegment(raw: String): String =
    raw.split('/').joinToString("/") {
        URLEncoder.encode(it, "UTF-8").replace("+", "%20")
    }
