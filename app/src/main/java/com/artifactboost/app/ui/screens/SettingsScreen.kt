package com.artifactboost.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.artifactboost.app.ArtifactBoostApp
import com.artifactboost.app.data.AccelerationSettings
import com.artifactboost.app.data.DownloadRoute
import com.artifactboost.app.data.RouteMode
import com.artifactboost.app.data.RouteProbe
import com.artifactboost.app.data.ScoredRoute
import com.artifactboost.app.ui.components.CardSurface
import com.artifactboost.app.ui.components.Hairline
import com.artifactboost.app.ui.components.IconBadge
import com.artifactboost.app.ui.components.InlineBanner
import com.artifactboost.app.ui.components.StatusPill
import com.artifactboost.app.ui.theme.AppTheme
import com.artifactboost.app.util.formatSpeed
import com.artifactboost.app.util.formatTimestamp
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 设置页一次测速的总限时：找目标 20s + 逐通道测速 15s + 余量 */
private const val SPEED_TEST_TOTAL_TIMEOUT_MS = 35_000L

/**
 * 设置：账户 + 加速设置（并发 / 通道）+ 通道测速 + 关于。
 * 对应 iOS 版的 SettingsView。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val colors = AppTheme.colors
    val app = ArtifactBoostApp.instance
    val session = app.session
    val downloads = app.downloads
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()

    val user by session.user.collectAsStateWithLifecycle()

    var settings by remember { mutableStateOf(AccelerationSettings.load(context)) }
    var isTesting by remember { mutableStateOf(false) }
    var testResults by remember { mutableStateOf<List<ScoredRoute>>(emptyList()) }
    var testTargetLabel by remember { mutableStateOf<String?>(null) }
    var testMessage by remember { mutableStateOf<String?>(null) }
    var testFailed by remember { mutableStateOf(false) }

    // 下载过程中可能自动记录过测速结果，回到设置页时同步一下
    LaunchedEffect(Unit) {
        settings = AccelerationSettings.load(context)
    }

    fun persist(updated: AccelerationSettings) {
        settings = updated
        updated.save(context)
    }

    // 用局部 val 而不是 fun：必须在 Scaffold 之前定义，onClick 才捕获得到
    val runSpeedTest: suspend () -> Unit = runSpeedTest@{
        isTesting = true
        testResults = emptyList()
        testMessage = null
        testFailed = false

        try {
            // 总限时：找目标与逐通道测速各自还有子超时，这里是最后一道保险；
            // 超时后按已有部分结果结算，避免转圈一分钟以上。
            val timedOut = withTimeoutOrNull(SPEED_TEST_TOTAL_TIMEOUT_MS) {
                val target = downloads.findTestTarget()
                if (target == null) {
                    testFailed = true
                    testMessage = "没找到可用的测速对象：至少需要一个跑过 Actions 的仓库（有产物或日志）。"
                    return@withTimeoutOrNull false
                }

                testTargetLabel = target.label
                var candidates = settings.candidateRoutes(target.isPrivate)
                if (target.isPrivate) candidates = listOf(DownloadRoute.DIRECT)

                val measured = RouteProbe.measureAll(candidates, target.url, knownSize = target.size)
                testResults = measured

                val best = measured.firstOrNull()
                if (best == null) {
                    testFailed = true
                    testMessage = "测速失败：所有通道都没取到数据，请检查网络后重试。"
                    return@withTimeoutOrNull false
                }

                persist(settings.record(best.route, best.speed))
                var text = "已保存：${if (best.route.isDirect) "直连" else best.route.name} · ${formatSpeed(best.speed)}"
                if (target.isPrivate) {
                    text += "（测速对象来自私有仓库，只测了直连，不会把地址交给镜像）"
                }
                if (measured.size < candidates.size) {
                    text += "（部分通道超时已跳过）"
                }
                testMessage = text
                false
            }
            if (timedOut == null) {
                testFailed = true
                if (testResults.isEmpty()) {
                    testMessage = "测速超时（35s）：所有通道都太慢，已取消，请换网络后重试。"
                } else {
                    // 已有部分结果时上面已 persist 最快通道，这里只补一句说明
                    testMessage = (testMessage ?: "已保存最快通道") + "（整体超时，部分慢通道已跳过）"
                }
            }
        } finally {
            isTesting = false
        }
    }

    Scaffold(
        containerColor = colors.canvas,
        topBar = {
            TopAppBar(
                title = { Text("设置", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = colors.strongText) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surface),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // MARK: - 账户
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SettingsHeader("账户")
                    CardSurface {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                val avatar = user?.avatarUrl
                                if (avatar != null) {
                                    AsyncImage(
                                        model = avatar,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(46.dp)
                                            .clip(CircleShape),
                                    )
                                } else {
                                    IconBadge(Icons.Filled.Person, colors.blue, size = 46.dp)
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(
                                        user?.login ?: "已登录",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = colors.strongText,
                                    )
                                    Text(
                                        "Token 保存在本机加密存储",
                                        fontSize = 11.sp,
                                        color = colors.subtle,
                                    )
                                }
                                Spacer(Modifier.weight(1f))
                            }

                            Hairline()

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { session.logout() }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Logout,
                                    contentDescription = null,
                                    tint = colors.red,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text("退出登录", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.red)
                            }
                        }
                    }
                }
            }

            // MARK: - 加速设置
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SettingsHeader("加速设置")
                    CardSurface {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("并发连接数", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.strongText)
                                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                    AccelerationSettings.CONNECTION_OPTIONS.forEachIndexed { index, count ->
                                        SegmentedButton(
                                            selected = settings.connections == count,
                                            onClick = { persist(settings.copy(connections = count)) },
                                            shape = SegmentedButtonDefaults.itemShape(
                                                index = index,
                                                count = AccelerationSettings.CONNECTION_OPTIONS.size,
                                            ),
                                        ) { Text("$count", fontSize = 13.sp) }
                                    }
                                }
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("下载通道", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.strongText)
                                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                    RouteMode.entries.forEachIndexed { index, mode ->
                                        SegmentedButton(
                                            selected = settings.mode == mode,
                                            onClick = {
                                                persist(settings.copy(mode = mode))
                                                testResults = emptyList()
                                                testMessage = null
                                            },
                                            shape = SegmentedButtonDefaults.itemShape(
                                                index = index,
                                                count = RouteMode.entries.size,
                                            ),
                                        ) { Text(mode.title, fontSize = 12.sp) }
                                    }
                                }
                            }

                            if (settings.mode == RouteMode.CUSTOM) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("加速前缀", fontSize = 12.sp, color = colors.muted)
                                    OutlinedTextField(
                                        value = settings.customPrefix,
                                        onValueChange = { persist(settings.copy(customPrefix = it)) },
                                        placeholder = { Text("https://你的中转地址/", fontSize = 13.sp) },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }

                            Hairline()

                            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(settings.mode.detail, fontSize = 11.sp, color = colors.subtle)
                                Text(
                                    "并发数越大越能跑满带宽；绿色网络环境建议 32~64，一般 16 即可，千兆内网/高速 Wi-Fi 可试 128。被限流时引擎会自动退让并把活儿转给健康通道，不会失败。设置会自动保存，下载时直接生效。",
                                    fontSize = 11.sp,
                                    color = colors.subtle,
                                )
                            }
                        }
                    }
                }
            }

            // MARK: - 通道测速
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SettingsHeader("通道测速")
                    CardSurface {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(enabled = !isTesting) {
                                        scope.launch { runSpeedTest() }
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.TrendingUp,
                                    contentDescription = null,
                                    tint = colors.blue,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(
                                    "测速并保存最快通道",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isTesting) colors.subtle else colors.strongText,
                                )
                                Spacer(Modifier.weight(1f))
                                if (isTesting) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                }
                            }

                            val testedRoute = settings.testedRoute
                            val testedAt = settings.testedAtMillis
                            if (testedRoute != null && testedAt != null) {
                                Hairline()
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    IconBadge(
                                        if (testedRoute.isDirect) Icons.Filled.CheckCircle else Icons.Filled.Cloud,
                                        if (testedRoute.isDirect) colors.orange else colors.green,
                                        size = 32.dp,
                                    )
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            if (testedRoute.isDirect) "直连" else testedRoute.name,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = colors.strongText,
                                        )
                                        Text(
                                            "已保存 · ${formatSpeed(settings.testedSpeed)} · ${formatTimestamp(testedAt)}",
                                            fontSize = 11.sp,
                                            color = colors.subtle,
                                        )
                                    }
                                    Spacer(Modifier.weight(1f))
                                    StatusPill("当前使用", colors.green)
                                }
                            }

                            if (testResults.isNotEmpty()) {
                                Hairline()
                                testResults.forEachIndexed { index, result ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        IconBadge(
                                            if (result.route.isDirect) Icons.Filled.CheckCircle else Icons.Filled.Cloud,
                                            if (result.route.isDirect) colors.orange else colors.blue,
                                            size = 30.dp,
                                        )
                                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(
                                                if (result.route.isDirect) "直连" else result.route.name,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = colors.strongText,
                                            )
                                            testTargetLabel?.let {
                                                Text(it, fontSize = 11.sp, color = colors.subtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                        Spacer(Modifier.weight(1f))
                                        Text(
                                            formatSpeed(result.speed),
                                            fontSize = 12.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = if (index == 0) colors.green else colors.muted,
                                        )
                                    }
                                }
                            }

                            val message = testMessage
                            if (message != null) {
                                InlineBanner(
                                    message,
                                    if (testFailed) colors.orange else colors.green,
                                    if (testFailed) Icons.Filled.Warning else Icons.Filled.CheckCircle,
                                )
                            }

                            Text(
                                "测速会拿一个真实的下载目标（优先用你自己仓库里最新的构建产物）分别测试每条通道，把最快的保存下来。之后所有下载都直接用它，不用每次现测。",
                                fontSize = 11.sp,
                                color = colors.subtle,
                            )
                        }
                    }
                }
            }

            // MARK: - 关于
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SettingsHeader("关于")
                    CardSurface {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("版本", fontSize = 13.sp, color = colors.muted)
                                Spacer(Modifier.weight(1f))
                                Text("1.2", fontSize = 13.sp, color = colors.strongText)
                            }
                            Hairline()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { uriHandler.openUri("https://github.com/yitenchen123/ArtifactBoost") }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.OpenInNew,
                                    contentDescription = null,
                                    tint = colors.blue,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text("项目主页 / 自建中转教程", fontSize = 13.sp, color = colors.blue)
                            }
                            Hairline()
                            Text(
                                "智能加速与自定义通道可能让产物数据经过第三方中转，私有仓库会自动强制走直连。Token 全程只在本机使用。\n\n" +
                                    "智能加速会额外尝试 ghfast.top —— 它只认 github.com 原始地址，因此仅对「发行版」附件生效；构建产物与日志仍走其它镜像。",
                                fontSize = 11.sp,
                                color = colors.subtle,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsHeader(title: String) {
    val colors = AppTheme.colors
    Text(
        title,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = colors.muted,
        modifier = Modifier.padding(start = 4.dp),
    )
}
