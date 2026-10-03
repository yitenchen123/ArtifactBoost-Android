package com.artifactboost.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.artifactboost.app.ArtifactBoostApp
import com.artifactboost.app.data.GHRelease
import com.artifactboost.app.data.GHRepo
import com.artifactboost.app.data.GHWorkflowRun
import com.artifactboost.app.ui.theme.AppTheme

private enum class RootTab(val label: String, val icon: ImageVector) {
    Repos("仓库", Icons.Filled.GridView),
    Search("搜索", Icons.Filled.Search),
    Downloads("下载", Icons.Filled.ArrowDownward),
    Settings("设置", Icons.Filled.Settings),
}

/**
 * 登录后的主界面：仓库 / 搜索 / 下载 / 设置。
 * 对应 iOS 版的 RootTabView（每个 tab 一个 NavigationStack）。
 */
@Composable
fun RootScreen() {
    val colors = AppTheme.colors
    val downloads = ArtifactBoostApp.instance.downloads
    val states by downloads.states.collectAsStateWithLifecycle()

    var selected by remember { mutableStateOf(RootTab.Repos) }
    val activeCount = states.values.count {
        it is com.artifactboost.app.download.DownloadState.Downloading ||
            it is com.artifactboost.app.download.DownloadState.Resolving
    }

    // 侧滑返回时，先回到「仓库」这个首页 Tab，再退出 App
    BackHandler(enabled = selected != RootTab.Repos) {
        selected = RootTab.Repos
    }

    Scaffold(
        containerColor = colors.canvas,
        bottomBar = {
            NavigationBar(containerColor = colors.surface) {
                RootTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selected == tab,
                        onClick = { selected = tab },
                        icon = {
                            if (tab == RootTab.Downloads && activeCount > 0) {
                                BadgedBox(badge = { Badge { Text("$activeCount") } }) {
                                    Icon(tab.icon, contentDescription = tab.label)
                                }
                            } else {
                                Icon(tab.icon, contentDescription = tab.label)
                            }
                        },
                        label = { Text(tab.label, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = colors.blue,
                            selectedTextColor = colors.blue,
                            unselectedIconColor = colors.muted,
                            unselectedTextColor = colors.muted,
                            indicatorColor = colors.blue.copy(alpha = 0.12f),
                        ),
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (selected) {
                // 内部栈到底了就让系统处理（退出 App）
                RootTab.Repos -> ReposTabNavHost(onBackAtRoot = { false })
                RootTab.Search -> SearchTabNavHost(onBackAtRoot = { false })
                RootTab.Downloads -> DownloadsScreen()
                RootTab.Settings -> SettingsScreen()
            }
        }
    }
}

/**
 * 仓库 tab 的导航栈：列表 → 仓库详情 → 构建详情 / 发行版详情。
 * 详情页用既有的列表数据按 id 回查，避免重复请求和复杂的参数序列化。
 */
@Composable
private fun ReposTabNavHost(onBackAtRoot: () -> Boolean) {
    val navController = rememberNavController()
    val app = ArtifactBoostApp.instance

    /** 页面之间传引用：导航只用 id，数据从这份缓存里取 */
    val repoById = remember { mutableMapOf<String, GHRepo>() }
    val runById = remember { mutableMapOf<String, GHWorkflowRun>() }
    val releaseById = remember { mutableMapOf<String, GHRelease>() }

    // 系统侧滑 / 返回键先交给这条内部导航栈消费，栈底时才上抛给外层
    BackHandler(enabled = true) {
        if (!navController.popBackStack()) {
            onBackAtRoot()
        }
    }

    NavHost(navController = navController, startDestination = "repos") {
        composable("repos") {
            RepoListScreen(
                onOpenRepo = { repo ->
                    repoById[repo.fullName] = repo
                    navController.navigate("repo/${java.net.URLEncoder.encode(repo.fullName, "UTF-8")}")
                },
            )
        }

        composable(
            route = "repo/{fullName}",
            arguments = listOf(navArgument("fullName") { type = NavType.StringType }),
        ) { entry ->
            val fullName = java.net.URLDecoder.decode(
                entry.arguments?.getString("fullName").orEmpty(),
                "UTF-8",
            )
            val repo = repoById[fullName] ?: return@composable

            RepoDetailScreen(
                repo = repo,
                onBack = { navController.popBackStack() },
                onOpenRun = { run ->
                    runById["${repo.fullName}#${run.id}"] = run
                    navController.navigate("run/${java.net.URLEncoder.encode(repo.fullName, "UTF-8")}/${run.id}")
                },
                onOpenRelease = { release ->
                    releaseById["${repo.fullName}#${release.id}"] = release
                    navController.navigate("release/${java.net.URLEncoder.encode(repo.fullName, "UTF-8")}/${release.id}")
                },
            )
        }

        composable(
            route = "run/{fullName}/{runId}",
            arguments = listOf(
                navArgument("fullName") { type = NavType.StringType },
                navArgument("runId") { type = NavType.LongType },
            ),
        ) { entry ->
            val fullName = java.net.URLDecoder.decode(
                entry.arguments?.getString("fullName").orEmpty(),
                "UTF-8",
            )
            val runId = entry.arguments?.getLong("runId") ?: return@composable
            val repo = repoById[fullName] ?: return@composable
            val run = runById["$fullName#$runId"] ?: return@composable

            RunDetailScreen(repo = repo, run = run, onBack = { navController.popBackStack() })
        }

        composable(
            route = "release/{fullName}/{releaseId}",
            arguments = listOf(
                navArgument("fullName") { type = NavType.StringType },
                navArgument("releaseId") { type = NavType.LongType },
            ),
        ) { entry ->
            val fullName = java.net.URLDecoder.decode(
                entry.arguments?.getString("fullName").orEmpty(),
                "UTF-8",
            )
            val releaseId = entry.arguments?.getLong("releaseId") ?: return@composable
            val repo = repoById[fullName] ?: return@composable
            val release = releaseById["$fullName#$releaseId"] ?: return@composable

            ReleaseDetailScreen(repo = repo, release = release, onBack = { navController.popBackStack() })
        }
    }
}

/**
 * 搜索 tab 的导航栈：搜索 → 仓库详情 → 构建详情 / 发行版详情。
 * 之前这里直接放 SearchScreen()（onOpenRepo 默认空实现），
 * 导致直接打开和搜索结果点击都跳不动；与 iOS 版 NavigationStack 对齐，各 tab 独立栈。
 */
@Composable
private fun SearchTabNavHost(onBackAtRoot: () -> Boolean) {
    val navController = rememberNavController()

    /** 页面之间传引用：导航只用 id，数据从这份缓存里取 */
    val repoById = remember { mutableMapOf<String, GHRepo>() }
    val runById = remember { mutableMapOf<String, GHWorkflowRun>() }
    val releaseById = remember { mutableMapOf<String, GHRelease>() }

    // 系统侧滑 / 返回键先交给这条内部导航栈消费，栈底时才上抛给外层
    BackHandler(enabled = true) {
        if (!navController.popBackStack()) {
            onBackAtRoot()
        }
    }

    NavHost(navController = navController, startDestination = "search") {
        composable("search") {
            SearchScreen(
                onOpenRepo = { repo ->
                    repoById[repo.fullName] = repo
                    navController.navigate("repo/${java.net.URLEncoder.encode(repo.fullName, "UTF-8")}")
                },
            )
        }

        composable(
            route = "repo/{fullName}",
            arguments = listOf(navArgument("fullName") { type = NavType.StringType }),
        ) { entry ->
            val fullName = java.net.URLDecoder.decode(
                entry.arguments?.getString("fullName").orEmpty(),
                "UTF-8",
            )
            val repo = repoById[fullName] ?: return@composable

            RepoDetailScreen(
                repo = repo,
                onBack = { navController.popBackStack() },
                onOpenRun = { run ->
                    runById["${repo.fullName}#${run.id}"] = run
                    navController.navigate("run/${java.net.URLEncoder.encode(repo.fullName, "UTF-8")}/${run.id}")
                },
                onOpenRelease = { release ->
                    releaseById["${repo.fullName}#${release.id}"] = release
                    navController.navigate("release/${java.net.URLEncoder.encode(repo.fullName, "UTF-8")}/${release.id}")
                },
            )
        }

        composable(
            route = "run/{fullName}/{runId}",
            arguments = listOf(
                navArgument("fullName") { type = NavType.StringType },
                navArgument("runId") { type = NavType.LongType },
            ),
        ) { entry ->
            val fullName = java.net.URLDecoder.decode(
                entry.arguments?.getString("fullName").orEmpty(),
                "UTF-8",
            )
            val runId = entry.arguments?.getLong("runId") ?: return@composable
            val repo = repoById[fullName] ?: return@composable
            val run = runById["$fullName#$runId"] ?: return@composable

            RunDetailScreen(repo = repo, run = run, onBack = { navController.popBackStack() })
        }

        composable(
            route = "release/{fullName}/{releaseId}",
            arguments = listOf(
                navArgument("fullName") { type = NavType.StringType },
                navArgument("releaseId") { type = NavType.LongType },
            ),
        ) { entry ->
            val fullName = java.net.URLDecoder.decode(
                entry.arguments?.getString("fullName").orEmpty(),
                "UTF-8",
            )
            val releaseId = entry.arguments?.getLong("releaseId") ?: return@composable
            val repo = repoById[fullName] ?: return@composable
            val release = releaseById["$fullName#$releaseId"] ?: return@composable

            ReleaseDetailScreen(repo = repo, release = release, onBack = { navController.popBackStack() })
        }
    }
}
