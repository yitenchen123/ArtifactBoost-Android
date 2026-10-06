package com.artifactboost.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.artifactboost.app.ArtifactBoostApp
import com.artifactboost.app.data.AccelerationSettings
import com.artifactboost.app.data.DownloadItem
import com.artifactboost.app.data.DownloadSource
import com.artifactboost.app.download.DownloadState
import com.artifactboost.app.ui.components.GradientProgressBar
import com.artifactboost.app.ui.components.IconBadge
import com.artifactboost.app.ui.components.IconBadgeButton
import com.artifactboost.app.ui.components.LaneHeatStrip
import com.artifactboost.app.ui.components.SpeedBadge
import com.artifactboost.app.ui.components.SpeedSparkline
import com.artifactboost.app.ui.components.StatusPill
import com.artifactboost.app.ui.components.brandGradient
import com.artifactboost.app.ui.theme.AppColors
import com.artifactboost.app.ui.theme.AppTheme
import com.artifactboost.app.util.formatBytes

/** 按下载类型取语义色 */
fun sourceColor(source: DownloadSource, colors: AppColors): Color = when (source) {
    is DownloadSource.Artifact -> colors.blue
    is DownloadSource.RunLogs -> colors.orange
    is DownloadSource.ReleaseAsset -> colors.purple
    is DownloadSource.SourceArchive -> colors.green
}

/** 按下载类型取图标 */
fun sourceIcon(source: DownloadSource): ImageVector = when (source) {
    is DownloadSource.Artifact -> Icons.Filled.Archive
    is DownloadSource.RunLogs -> Icons.Filled.Description
    is DownloadSource.ReleaseAsset -> Icons.Filled.Inventory2
    is DownloadSource.SourceArchive -> Icons.Filled.Code
}

/**
 * 类型小标签（构建产物 / 日志 / 附件 / 源码包）
 */
@Composable
private fun KindChip(text: String, color: Color) {
    Text(
        text = text,
        fontSize = 9.sp,
        fontWeight = FontWeight.SemiBold,
        color = color,
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * 通用「可下载项」行：产物 / 构建日志 / 发行版附件 / 源码包 共用。
 *
 * 视觉分层：
 *  1. 顶部「图标 + 标题 + 元信息」，与 GitHub 移动端仓库行同一套语言；
 *  2. 中部是**自适应**的操作区 —— 没下载时是渐变主按钮，
 *     下载中变成「渐变进度条 + 速度徽标 + 连接热力条」，完成后收成一条成功态；
 *  3. 底部保留次要操作（详细信息 / 取消 / 重试），默认低调。
 */
@Composable
fun DownloadItemRow(
    item: DownloadItem,
    disabled: Boolean = false,
    disabledNote: String? = null,
) {
    val colors = AppTheme.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    val downloads = ArtifactBoostApp.instance.downloads

    val states by downloads.states.collectAsStateWithLifecycle()
    val summaries by downloads.routeSummary.collectAsStateWithLifecycle()
    val state = states[item.id] ?: DownloadState.Idle
    val summary = summaries[item.id]

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 头部
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(sourceIcon(item.source), sourceColor(item.source, colors), size = 38.dp)

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    item.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.strongText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(item.subtitle, fontSize = 11.sp, color = colors.muted, maxLines = 1)

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    KindChip(item.kindName, sourceColor(item.source, colors))
                    if (item.size != null) {
                        Text(formatBytes(item.size), fontSize = 11.sp, color = colors.subtle)
                    }
                    if (!item.source.supportsChunkedDownload) {
                        Text(
                            "单连接",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.orange,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(colors.orange.copy(alpha = 0.12f))
                                .padding(horizontal = 5.dp, vertical = 1.5.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            if (disabled && disabledNote != null) {
                StatusPill(disabledNote, colors.subtle)
            }
        }

        // 操作区
        when (state) {
            DownloadState.Idle -> {
                Button(
                    onClick = {
                        downloads.start(item, AccelerationSettings.load(context))
                        com.artifactboost.app.download.DownloadService.start(context)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp),
                    enabled = !disabled,
                    shape = MaterialTheme.shapes.small,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = Color.White,
                        disabledContainerColor = Color.Transparent,
                    ),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(brandGradient(colors)),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("加速下载", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            DownloadState.Resolving -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = colors.blue,
                        strokeWidth = 2.dp,
                    )
                    Text(summary ?: "正在解析下载地址…", fontSize = 12.sp, color = colors.muted)
                }
            }

            is DownloadState.Downloading -> {
                DownloadingSection(item = item, progress = state.progress, summary = summary)
            }

            is DownloadState.Finished -> {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = colors.green,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            "下载完成",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.strongText,
                        )
                    }
                    if (state.publicPath != null) {
                        Text("已保存到系统目录：${state.publicPath}", fontSize = 11.sp, color = colors.muted)
                    }
                    if (summary != null) {
                        Text(summary, fontSize = 11.sp, color = colors.muted)
                    } else if (state.publicPath == null) {
                        Text(
                            "公共目录写入失败，已保留在 App 私有目录，可手动导出。",
                            fontSize = 11.sp,
                            color = colors.muted,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                val uri = state.publicUri
                                if (uri != null) sharePublicUri(context, uri, state.file.name)
                                else shareFile(context, state.file)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp),
                            shape = MaterialTheme.shapes.small,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.green,
                                contentColor = Color.White,
                            ),
                        ) {
                            Text(
                                if (state.publicUri != null) "分享 / 打开系统文件" else "导出 / 保存到文件",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        IconBadgeButton(
                            icon = Icons.Filled.Refresh,
                            onClick = {
                                downloads.start(item, AccelerationSettings.load(context))
                                com.artifactboost.app.download.DownloadService.start(context)
                            },
                        )
                    }
                }
            }

            is DownloadState.Failed -> {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = null,
                            tint = colors.red,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(state.message, fontSize = 12.sp, color = colors.muted)
                    }
                    Button(
                        onClick = {
                            downloads.start(item, AccelerationSettings.load(context))
                            com.artifactboost.app.download.DownloadService.start(context)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(38.dp),
                        shape = MaterialTheme.shapes.small,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.red.copy(alpha = 0.1f),
                            contentColor = colors.red,
                        ),
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("重试", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/**
 * 下载中区块：渐变进度条 + 百分比 + 速度徽标 + 连接热力条 + 速度趋势线。
 * 卡住时显示「正在自动重建」提示。
 */
@Composable
private fun DownloadingSection(
    item: DownloadItem,
    progress: com.artifactboost.app.download.DownloadProgress,
    summary: String?,
) {
    val colors = AppTheme.colors
    val downloads = ArtifactBoostApp.instance.downloads
    var showDetails by remember { mutableStateOf(false) }
    // 速度趋势采样：把最近若干拍的速度画成一条线
    val speedSamples = remember { mutableStateListOf<Double>() }

    val diagnostics = progress.diagnostics
    val stalled = diagnostics?.stalled == true

    if (progress.speedBytesPerSecond > 0) {
        androidx.compose.runtime.LaunchedEffect(progress.speedBytesPerSecond) {
            speedSamples.add(progress.speedBytesPerSecond)
            if (speedSamples.size > 32) speedSamples.removeAt(0)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        if (progress.totalBytes > 0) {
            GradientProgressBar(fraction = progress.fraction, height = 9.dp, stalled = stalled)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${(progress.fraction * 100).toInt()}%",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = colors.strongText,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "${formatBytes(progress.downloadedBytes)} / ${formatBytes(progress.totalBytes)}",
                    fontSize = 11.sp,
                    color = colors.muted,
                )
                Spacer(Modifier.weight(1f))
                SpeedBadge(progress.speedBytesPerSecond)
            }
        } else {
            // 探测不到体积（单连接流式）：给不确定进度的动画条 + 速度
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = colors.blue,
                    strokeWidth = 2.dp,
                )
                Text("单连接下载中…", fontSize = 12.sp, color = colors.muted)
                Spacer(Modifier.weight(1f))
                SpeedBadge(progress.speedBytesPerSecond)
            }
        }

        // 分段热力条：把「哪条连接在跑」画出来，比读明细表快得多
        if (diagnostics != null && diagnostics.lanes.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                LaneHeatStrip(diagnostics.lanes)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("${diagnostics.lanes.size} 条连接", fontSize = 10.sp, color = colors.subtle)
                    if (diagnostics.adaptiveWindow > 0) {
                        Text("· 自适应 ${diagnostics.adaptiveWindow}", fontSize = 10.sp, color = colors.subtle)
                    }
                    if (diagnostics.throttles > 0) {
                        Text(
                            "· 限流 ${diagnostics.throttles}",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.orange,
                        )
                    }
                }
            }
        }

        if (stalled) {
            Text(
                "检测到连接卡住，正在自动重建…",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.orange,
            )
        } else if (summary != null) {
            Text(summary, fontSize = 11.sp, color = colors.subtle)
        }

        // 速度趋势迷你线：一眼看出速度在涨还是在掉
        if (speedSamples.size >= 4) {
            SpeedSparkline(speedSamples.toList())
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            TextButton(
                onClick = { showDetails = true },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) {
                Text("详细信息", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = colors.blue)
            }
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = { downloads.cancel(item) },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) {
                Text("取消", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = colors.red)
            }
        }
    }

    if (showDetails) {
        DownloadDetailsSheet(
            title = item.title,
            // 边下边看：面板里的数据每次都从最新一帧进度里取，
            // 所以重开面板看到的永远是「此刻」的明细。
            diagnostics = diagnostics,
            onDismiss = { showDetails = false },
        )
    }
}
