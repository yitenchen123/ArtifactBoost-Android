package com.artifactboost.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.artifactboost.app.ArtifactBoostApp
import com.artifactboost.app.data.AccelerationSettings
import com.artifactboost.app.data.DownloadRoute
import com.artifactboost.app.data.RouteMode
import com.artifactboost.app.ui.components.CardSurface
import com.artifactboost.app.ui.components.Hairline
import com.artifactboost.app.ui.components.IconBadge
import com.artifactboost.app.ui.theme.AppTheme

/**
 * 设置：账户 + 加速设置（并发 / 通道）+ 关于。
 * 对应 iOS 版的 SettingsView。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen() {
    val colors = AppTheme.colors
    val app = ArtifactBoostApp.instance
    val session = app.session
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val haptics = LocalHapticFeedback.current

    val user by session.user.collectAsStateWithLifecycle()

    var settings by remember { mutableStateOf(AccelerationSettings.load(context)) }
    // 原神彩蛋兜底入口：长按顶部「设置」标题同样触发（底部 Tab 手势若被系统消费时仍可发现）
    var showGenshinEgg by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        settings = AccelerationSettings.load(context)
    }

    fun persist(updated: AccelerationSettings) {
        settings = updated
        updated.save(context)
    }

    Scaffold(
        containerColor = colors.canvas,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "设置",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = colors.strongText,
                        modifier = Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                showGenshinEgg = true
                            },
                            onLongClickLabel = "发现彩蛋",
                        ),
                    )
                },
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

                            Hairline()

                            Text(
                                "并发数越大越能跑满带宽；绿色网络环境建议 32~64，一般 16 即可，千兆内网/高速 Wi-Fi 可试 128。被限流时引擎会自动退让并把活儿转给健康通道，不会失败。设置会自动保存，下载时直接生效。",
                                fontSize = 11.sp,
                                color = colors.subtle,
                            )
                        }
                    }
                }
            }

            // MARK: - 下载源
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SettingsHeader("下载源")
                    CardSurface {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                SourceButton(
                                    title = "官方源",
                                    subtitle = "直连 GitHub",
                                    icon = Icons.Filled.Cloud,
                                    selected = settings.mode == RouteMode.DIRECT,
                                    accent = colors.blue,
                                    onClick = { persist(settings.copy(mode = RouteMode.DIRECT)) },
                                )
                                SourceButton(
                                    title = "镜像加速",
                                    subtitle = "多通道并行 · 推荐",
                                    icon = Icons.Filled.Bolt,
                                    selected = settings.mode == RouteMode.SMART,
                                    accent = colors.green,
                                    onClick = { persist(settings.copy(mode = RouteMode.SMART)) },
                                )
                            }

                            Hairline()

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("自建中转", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.strongText)
                                    Text("用自己的反代地址下载", fontSize = 11.sp, color = colors.subtle)
                                }
                                Spacer(Modifier.weight(1f))
                                Switch(
                                    checked = settings.mode == RouteMode.CUSTOM,
                                    onCheckedChange = { enabled ->
                                        persist(
                                            settings.copy(
                                                mode = if (enabled) RouteMode.CUSTOM else RouteMode.SMART,
                                            ),
                                        )
                                    },
                                )
                            }

                            if (settings.mode == RouteMode.CUSTOM) {
                                OutlinedTextField(
                                    value = settings.customPrefix,
                                    onValueChange = { persist(settings.copy(customPrefix = it)) },
                                    placeholder = { Text("https://你的中转地址/", fontSize = 13.sp) },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                if (DownloadRoute.normalizedPrefix(settings.customPrefix).isEmpty()) {
                                    Text("前缀为空时将回退直连", fontSize = 11.sp, color = colors.orange)
                                }
                            }

                            Hairline()

                            Text(settings.mode.detail, fontSize = 11.sp, color = colors.subtle)
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

    if (showGenshinEgg) {
        GenshinEasterEggDialog(
            onDismiss = { showGenshinEgg = false },
            onOpenOfficialSite = { uriHandler.openUri(GENSHIN_CN_URL) },
        )
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

/** 下载源大按钮：选中时按语义色高亮，未选中时灰边。可复用给官方源 / 镜像加速。 */
@Composable
private fun RowScope.SourceButton(
    title: String,
    subtitle: String,
    icon: ImageVector,
    selected: Boolean,
    accent: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    val colors = AppTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.12f) else colors.canvas)
            .border(1.5.dp, if (selected) accent else colors.border, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected) accent else colors.muted,
            modifier = Modifier.size(22.dp),
        )
        Text(
            title,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = if (selected) accent else colors.strongText,
        )
        Text(subtitle, fontSize = 11.sp, color = colors.subtle)
    }
}
