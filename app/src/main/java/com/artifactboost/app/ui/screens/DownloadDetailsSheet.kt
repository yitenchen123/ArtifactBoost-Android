package com.artifactboost.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.artifactboost.app.download.DownloadDiagnostics
import com.artifactboost.app.download.LaneSnapshot
import com.artifactboost.app.download.SegmentState
import com.artifactboost.app.ui.components.GradientProgressBar
import com.artifactboost.app.ui.components.LaneHeatStrip
import com.artifactboost.app.ui.components.MetricTile
import com.artifactboost.app.ui.theme.AppTheme
import com.artifactboost.app.util.formatBytes
import com.artifactboost.app.util.formatSpeed

/**
 * 下载详情面板 —— 对应 Neat Download Manager 的「连接」视图。
 *
 * 展示三类信息（用户选定的口径）：
 *  1. 分段进度与速度：每条连接啃哪个字节区间、跑到百分比、当前多快；
 *  2. 分段连接状态：等待/下载中/重试中/完成/失败，第几次重试，服务端回了什么码；
 *  3. 下载地址与通道：每段走的是哪条镜像，以及当前实际请求的完整 URL（可一键复制）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadDetailsSheet(
    title: String,
    diagnostics: DownloadDiagnostics?,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 标题栏
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "下载详情",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.strongText,
                    )
                    Text(
                        title,
                        fontSize = 12.sp,
                        color = colors.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭", tint = colors.muted)
                }
            }

            if (diagnostics == null) {
                // 还没跑起来（正在解析地址 / 还没分段）：给个体面的占位，别显示空白
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "正在建立连接，稍候即可看到各分段明细…",
                        fontSize = 13.sp,
                        color = colors.muted,
                    )
                }
                return@Column
            }

            // 汇总
            SummaryHeader(diagnostics)

            // 各通道
            if (diagnostics.routes.isNotEmpty()) {
                RouteSection(diagnostics)
            }

            // 分段明细
            Text(
                "分段明细（${diagnostics.lanes.size}/${diagnostics.targetLanes} 条连接）",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.strongText,
            )
            if (diagnostics.lanes.isEmpty()) {
                Text("当前没有活跃分段", fontSize = 12.sp, color = colors.muted)
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(diagnostics.lanes, key = { it.laneId }) { lane ->
                        LaneCard(lane)
                    }
                }
            }

            // 下载地址
            if (diagnostics.activeUrl.isNotBlank()) {
                UrlSection(diagnostics.activeUrl)
            }
        }
    }
}

@Composable
private fun SummaryHeader(diagnostics: DownloadDiagnostics) {
    val colors = AppTheme.colors
    val totalSpeed = diagnostics.lanes.sumOf { it.speedBytesPerSecond }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // 大数字：实时总速度（这是用户最关心的一个数）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(colors.green.copy(alpha = 0.08f))
                .border(1.dp, colors.green.copy(alpha = 0.25f), MaterialTheme.shapes.medium)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text("实时总速度", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = colors.subtle)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Filled.Bolt, contentDescription = null, tint = colors.green, modifier = Modifier.size(15.dp))
                Text(
                    formatSpeed(totalSpeed),
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = colors.green,
                )
            }
        }

        // 分段热力条：颜色 = 状态
        if (diagnostics.lanes.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(colors.surface)
                    .border(1.dp, colors.border, MaterialTheme.shapes.medium)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LaneHeatStrip(diagnostics.lanes)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile(
                label = "活跃 / 目标",
                value = "${diagnostics.lanes.size} / ${diagnostics.targetLanes}",
                modifier = Modifier.weight(1f),
                tint = colors.blue,
            )
            MetricTile(
                label = "自适应窗口",
                value = if (diagnostics.adaptiveWindow > 0) "${diagnostics.adaptiveWindow}" else "—",
                modifier = Modifier.weight(1f),
                tint = colors.purple,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile(
                label = "切片 完成/累计",
                value = "${diagnostics.doneSlices} / ${diagnostics.totalSlices}",
                modifier = Modifier.weight(1f),
                tint = colors.muted,
            )
            MetricTile(
                label = "重试 / 切分",
                value = "${diagnostics.retries} / ${diagnostics.splits}",
                modifier = Modifier.weight(1f),
                tint = colors.orange,
            )
        }

        if (diagnostics.windowIncreases > 0 || diagnostics.windowDecreases > 0) {
            MetricTile(
                label = "窗口 涨/缩 · 峰值",
                value = "${diagnostics.windowIncreases} / ${diagnostics.windowDecreases} · ${diagnostics.windowPeak}",
                modifier = Modifier.fillMaxWidth(),
                tint = colors.blue,
            )
        }

        if (diagnostics.stalled) {
            Text(
                "检测到连接卡住：引擎已自动掐断并重建连接，速度会很快恢复。",
                fontSize = 11.sp,
                color = colors.orange,
            )
        }

        if (diagnostics.throttles > 0) {
            Text(
                "检测到服务端限流（429/503）：引擎已自动砍半并发并按指数退避重试，"
                    + "这是正常的自我节流，不是下载失败。",
                fontSize = 11.sp,
                color = colors.orange,
            )
        }

        Text(
            "说明：并发上限在「设置」里调整。引擎用 AIMD 自适应算法自己爬到服务器愿意给的并发 —— "
                + "「自适应窗口」就是当前实际在用的档位，遇到限流会自动砍半。",
            fontSize = 10.sp,
            color = colors.subtle,
        )
    }
}

@Composable
private fun RouteSection(diagnostics: DownloadDiagnostics) {
    val colors = AppTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "通道",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.strongText,
        )
        diagnostics.routes.forEach { route ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Filled.Speed,
                    contentDescription = null,
                    tint = if (route.isActive) colors.green else colors.muted,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    route.name,
                    fontSize = 12.sp,
                    color = colors.strongText,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    formatSpeed(route.speedBytesPerSecond),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (route.isActive) colors.green else colors.muted,
                )
            }
        }
    }
}

@Composable
private fun LaneCard(lane: LaneSnapshot) {
    val colors = AppTheme.colors
    val tint = stateColor(lane.state)
    val brush = Brush.horizontalGradient(listOf(tint.copy(alpha = 0.6f), tint))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(colors.canvas.copy(alpha = 0.5f))
            .border(1.dp, colors.border.copy(alpha = 0.5f), MaterialTheme.shapes.small)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "#${lane.laneId}",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = colors.strongText,
            )
            Spacer(Modifier.width(7.dp))
            Text(
                lane.routeName,
                fontSize = 10.sp,
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                lane.state.label,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = tint,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.12f))
                    .padding(horizontal = 5.dp, vertical = 1.5.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                formatSpeed(lane.speedBytesPerSecond),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = tint,
            )
        }

        GradientProgressBar(fraction = lane.fraction, height = 4.dp, tint = brush)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${formatBytes(lane.start)} – ${formatBytes(lane.end)}",
                fontSize = 9.sp,
                color = colors.subtle,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.weight(1f))
            if (lane.attempt > 1) {
                Text("第 ${lane.attempt} 次", fontSize = 9.sp, color = colors.orange)
                Spacer(Modifier.width(8.dp))
            }
            if (lane.lastStatus != null) {
                Text(
                    "HTTP ${lane.lastStatus}",
                    fontSize = 9.sp,
                    color = if (lane.lastStatus >= 400) colors.orange else colors.subtle,
                )
            }
        }
    }
}

@Composable
private fun UrlSection(url: String) {
    val colors = AppTheme.colors
    val clipboard = LocalClipboardManager.current

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "当前下载地址",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.strongText,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { clipboard.setText(AnnotatedString(url)) },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = "复制地址",
                    tint = colors.blue,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 120.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                url,
                fontSize = 11.sp,
                color = colors.muted,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/** 分段状态 → 语义色：完成绿、下载中蓝、重试橙、失败红 */
@Composable
private fun stateColor(state: SegmentState): Color {
    val colors = AppTheme.colors
    return when (state) {
        SegmentState.PENDING -> colors.muted
        SegmentState.DOWNLOADING -> colors.blue
        SegmentState.RETRYING -> colors.orange
        SegmentState.DONE -> colors.green
        SegmentState.FAILED -> colors.red
    }
}
