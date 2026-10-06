package com.artifactboost.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.artifactboost.app.ArtifactBoostApp
import com.artifactboost.app.data.DownloadItem
import com.artifactboost.app.ui.components.CardSurface
import com.artifactboost.app.ui.components.EmptyStateView
import com.artifactboost.app.ui.components.MetricTile
import com.artifactboost.app.ui.theme.AppTheme
import com.artifactboost.app.util.formatSpeed

/**
 * 下载中心：所有正在下载 / 已完成的任务。
 * 对应 iOS 版的 DownloadsView。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen() {
    val colors = AppTheme.colors
    val downloads = ArtifactBoostApp.instance.downloads
    val order by downloads.order.collectAsStateWithLifecycle()
    val itemsMap by downloads.items.collectAsStateWithLifecycle()
    val states by downloads.states.collectAsStateWithLifecycle()

    val items = order.mapNotNull { itemsMap[it] }
    val activeCount = states.values.count {
        it is com.artifactboost.app.download.DownloadState.Downloading ||
            it is com.artifactboost.app.download.DownloadState.Resolving
    }

    var menuOpen by remember { mutableStateOf(false) }
    var pendingRemove by remember { mutableStateOf<DownloadItem?>(null) }

    Scaffold(
        containerColor = colors.canvas,
        topBar = {
            TopAppBar(
                title = { Text("下载", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = colors.strongText) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surface),
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "更多", tint = colors.strongText)
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("清空已完成") },
                                onClick = {
                                    downloads.clearFinished()
                                    menuOpen = false
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (items.isEmpty()) {
                item {
                    EmptyStateView(
                        icon = Icons.Filled.ArrowDownward,
                        title = "还没有下载任务",
                        message = "去「仓库」里挑一个构建产物、发行版附件或源码包试试",
                    )
                }
            } else {
                // 实时总览卡片：用户在下载页一眼就能看到当前整机吞吐
                item {
                    OverviewCard(states = states, items = items)
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (activeCount > 0) "正在下载 $activeCount 个" else "全部下载",
                            fontSize = 12.sp,
                            color = colors.muted,
                        )
                        Spacer(Modifier.weight(1f))
                        if (activeCount > 0) {
                            Text(
                                formatSpeed(totalSpeed(states)),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                color = colors.green,
                            )
                        }
                    }
                }

                items(items, key = { it.id }) { item ->
                    CardSurface {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            DownloadItemRow(item = item)

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                TextButton(onClick = { pendingRemove = item }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = null,
                                        tint = colors.red,
                                        modifier = Modifier.size(15.dp),
                                    )
                                    Text("移除", color = colors.red, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                item {
                    Text(
                        "文件默认保存到系统 Download/ArtifactBoost/，可在文件管理器中查看；也可点「分享」发到其他 App。",
                        fontSize = 11.sp,
                        color = colors.subtle,
                    )
                }
            }
        }
    }

    val removing = pendingRemove
    if (removing != null) {
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("移除任务") },
            text = { Text("确定移除「${removing.title}」吗？已下载的文件不会被删除。") },
            confirmButton = {
                TextButton(onClick = {
                    downloads.remove(removing)
                    pendingRemove = null
                }) { Text("移除", color = colors.red) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) { Text("取消") }
            },
        )
    }
}

/** 所有在下载任务的速度之和 */
private fun totalSpeed(states: Map<String, com.artifactboost.app.download.DownloadState>): Double =
    states.values.sumOf {
        if (it is com.artifactboost.app.download.DownloadState.Downloading) {
            it.progress.speedBytesPerSecond
        } else {
            0.0
        }
    }

/**
 * 实时总览卡片：大号总速度 + 进行中/已完成/失败三块指标。
 */
@Composable
private fun OverviewCard(
    states: Map<String, com.artifactboost.app.download.DownloadState>,
    items: List<DownloadItem>,
) {
    val colors = AppTheme.colors
    val active = items.count {
        states[it.id] is com.artifactboost.app.download.DownloadState.Downloading ||
            states[it.id] is com.artifactboost.app.download.DownloadState.Resolving
    }
    val finished = items.count { states[it.id] is com.artifactboost.app.download.DownloadState.Finished }
    val failed = items.count { states[it.id] is com.artifactboost.app.download.DownloadState.Failed }
    val speed = totalSpeed(states)

    CardSurface(padding = 14.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(colors.green.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (active > 0) Icons.Filled.Bolt else Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = colors.green,
                        modifier = Modifier.size(15.dp),
                    )
                }
                Column {
                    Text(
                        if (active > 0) "正在加速" else "空闲",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.strongText,
                    )
                    Text("自适应并发 · 多通道叠加", fontSize = 10.sp, color = colors.subtle)
                }
                Spacer(Modifier.weight(1f))
                if (active > 0) {
                    Text(
                        formatSpeed(speed),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = colors.green,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricTile(
                    label = "进行中",
                    value = "$active",
                    modifier = Modifier.weight(1f),
                    tint = colors.blue,
                    icon = Icons.Filled.ArrowDownward,
                )
                MetricTile(
                    label = "已完成",
                    value = "$finished",
                    modifier = Modifier.weight(1f),
                    tint = colors.green,
                    icon = Icons.Filled.CheckCircle,
                )
                MetricTile(
                    label = "失败",
                    value = "$failed",
                    modifier = Modifier.weight(1f),
                    tint = if (failed > 0) colors.red else colors.subtle,
                    icon = Icons.Filled.Warning,
                )
            }
        }
    }
}
