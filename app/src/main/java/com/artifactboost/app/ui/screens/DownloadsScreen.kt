package com.artifactboost.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SwipeLeft
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.artifactboost.app.ArtifactBoostApp
import com.artifactboost.app.data.DownloadItem
import com.artifactboost.app.ui.components.CardSurface
import com.artifactboost.app.ui.components.EmptyStateView
import com.artifactboost.app.ui.theme.AppTheme

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
                item {
                    Text(
                        if (activeCount > 0) "正在下载 $activeCount 个" else "全部下载",
                        fontSize = 12.sp,
                        color = colors.muted,
                    )
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
