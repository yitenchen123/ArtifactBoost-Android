package com.artifactboost.app

import com.artifactboost.app.data.AccelerationSettings
import com.artifactboost.app.data.DownloadRoute
import com.artifactboost.app.data.RouteMode
import com.artifactboost.app.data.RouteScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ghfast 镜像的「只给发行版用」这条规则必须有测试守住 ——
 * 一旦被误用（把 Azure 签名地址套上去），服务端会直接 400，
 * 用户看到的就是「明明是公开仓库却下载失败」。
 */
class DownloadRouteTest {

    private val githubUrl = "https://github.com/owner/repo/releases/download/v1.0/app.zip"

    @Test
    fun `公开仓库智能加速时 ghfast 进入候选`() {
        val settings = AccelerationSettings(mode = RouteMode.SMART)
        val routes = settings.candidateRoutes(isPrivateRepo = false, githubUrl = githubUrl)
        assertTrue(routes.any { it.name == "ghfast.top" })
    }

    @Test
    fun `没有 github 地址时 ghfast 被剔除`() {
        val settings = AccelerationSettings(mode = RouteMode.SMART)
        // 构建产物/日志没有稳定地址：传 null
        val routes = settings.candidateRoutes(isPrivateRepo = false, githubUrl = null)
        assertFalse(routes.any { it.name == "ghfast.top" })
        // 常规镜像仍然在
        assertTrue(routes.any { it.name == "gh-proxy.com" })
    }

    @Test
    fun `私有仓库不套任何镜像包括 ghfast`() {
        val settings = AccelerationSettings(mode = RouteMode.SMART)
        val routes = settings.candidateRoutes(isPrivateRepo = true, githubUrl = githubUrl)
        assertEquals(listOf(DownloadRoute.DIRECT), routes)
    }

    @Test
    fun `ghfast 只对 github 域名生效且拼接正确`() {
        val applied = DownloadRoute.GHFAST.apply(githubUrl)
        assertEquals("https://ghfast.top/https://github.com/owner/repo/releases/download/v1.0/app.zip", applied)
    }

    @Test
    fun `ghfast 的作用域标记为 GITHUB_ONLY`() {
        assertEquals(RouteScope.GITHUB_ONLY, DownloadRoute.GHFAST.scope)
        assertTrue(DownloadRoute.BUILT_IN_MIRRORS.all { it.scope == RouteScope.ANY })
        assertEquals(RouteScope.ANY, DownloadRoute.DIRECT.scope)
    }

    @Test
    fun `直连永远在所有模式下保留兜底`() {
        RouteMode.entries.forEach { mode ->
            val settings = AccelerationSettings(mode = mode)
            val routes = settings.candidateRoutes(isPrivateRepo = false, githubUrl = githubUrl)
            assertTrue("$mode 缺少直连兜底", routes.any { it.isDirect })
        }
    }
}
