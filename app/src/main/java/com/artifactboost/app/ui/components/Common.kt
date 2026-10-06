package com.artifactboost.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.artifactboost.app.download.LaneSnapshot
import com.artifactboost.app.download.SegmentState
import com.artifactboost.app.ui.theme.AppColors
import com.artifactboost.app.ui.theme.AppTheme
import com.artifactboost.app.ui.theme.languageColor
import com.artifactboost.app.util.formatSpeed

/** 圆角图标（GitHub 移动端的仓库 / 文件图标风格，圆角走 MD3 shape scale） */
@Composable
fun IconBadge(
    icon: ImageVector,
    color: Color,
    size: androidx.compose.ui.unit.Dp = 34.dp,
) {
    Box(
        modifier = Modifier
            .size(size)
            // MD3：容器形状统一从小号圆角取，不再各写各的
            .clip(MaterialTheme.shapes.small)
            .background(color.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(size * 0.52f),
        )
    }
}

/**
 * 灰底小胶囊（GitHub 的 label / badge 风格）。
 *
 * MD3 化的做法：形状与内边距对齐 MD3 `AssistChip`，
 * 但保留「按语义色自定义」的能力 —— 运行状态红/绿这类信息色
 * 不该被主题色替换掉。
 */
@Composable
fun StatusPill(
    text: String,
    color: Color,
    icon: ImageVector? = null,
) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(11.dp))
        }
        Text(
            text = text,
            // MD3 labelSmall：带字距的小标签
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
    }
}

/** 语言色点 + 名称 */
@Composable
fun LanguageLabel(language: String) {
    val colors = AppTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(languageColor(language, colors)),
        )
        Text(language, style = MaterialTheme.typography.labelSmall, color = colors.muted)
    }
}

/** 星标 / fork 之类的小统计 */
@Composable
fun StatLabel(icon: ImageVector, text: String) {
    val colors = AppTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(icon, contentDescription = null, tint = colors.muted, modifier = Modifier.size(12.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = colors.muted)
    }
}

/** 空状态 */
@Composable
fun EmptyStateView(
    icon: ImageVector,
    title: String,
    message: String? = null,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = colors.subtle, modifier = Modifier.size(34.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, color = colors.muted)
        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = colors.subtle,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 卡片容器 —— MD3 化的关键一处。
 *
 * 老实现是「自己画 Box + 背景 + 1dp 描边」，视觉上没问题，
 * 但它拿不到 MD3 的层级色（surfaceContainer*）、也没有 MD3 卡片的高度语义。
 * 改成 MD3 `OutlinedCard`：形状、描边、容器色都走主题，
 * 深浅色切换与动态取色都会自动跟上。
 */
@Composable
fun CardSurface(
    modifier: Modifier = Modifier,
    padding: androidx.compose.ui.unit.Dp = 14.dp,
    content: @Composable () -> Unit,
) {
    androidx.compose.material3.OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = androidx.compose.material3.CardDefaults.outlinedCardColors(
            containerColor = AppTheme.colors.surface,
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.colors.border),
    ) {
        Box(modifier = Modifier.padding(padding)) { content() }
    }
}

/** 错误 / 提示横幅 */
@Composable
fun InlineBanner(
    text: String,
    color: Color,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(color.copy(alpha = 0.1f))
            .border(1.dp, color.copy(alpha = 0.3f), MaterialTheme.shapes.small)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = colors.muted)
    }
}

/** 方形描边图标按钮（对齐 iOS 的 bordered 小按钮） */
@Composable
fun IconBadgeButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        shape = MaterialTheme.shapes.small,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
    ) {
        Icon(icon, contentDescription = null, tint = colors.strongText, modifier = Modifier.size(18.dp))
    }
}

/**
 * 分割线 —— MD3 化的 `HorizontalDivider`。
 *
 * 老的 `Hairline` 是手画 0.5dp 的 Box；MD3 的 `HorizontalDivider`
 * 自带正确的描边粗细与颜色语义（`outlineVariant`），
 * 在高 DPI 屏上不会被四舍五入成 1px 实线、也不会在深色下偏亮。
 */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    androidx.compose.material3.HorizontalDivider(
        modifier = modifier.fillMaxWidth(),
        thickness = androidx.compose.ui.unit.Dp.Hairline,
        color = AppTheme.colors.border,
    )
}

/** 加载中的骨架条 */
@Composable
fun SkeletonBar(height: androidx.compose.ui.unit.Dp = 12.dp, widthFraction: Float = 1f) {
    val colors = AppTheme.colors
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.7f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(750),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeletonAlpha",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .alpha(alpha)
            .background(colors.border),
    )
}

/** 骨架卡片：给 README / 列表加载时占位 */
@Composable
fun SkeletonBlock(lines: Int = 4) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        repeat(lines) { index ->
            Row(modifier = Modifier.fillMaxWidth()) {
                SkeletonBar(
                    height = if (index == 0) 16.dp else 11.dp,
                    widthFraction = if (index == lines - 1) 0.5f else 1f,
                )
                Spacer(Modifier.width(0.dp))
            }
        }
    }
}

// MARK: - 视觉升级组件（与 iOS 版 Theme.swift 一一对应）

/** 品牌渐变（加速相关的强调元素统一用它） */
fun brandGradient(colors: AppColors): Brush = Brush.linearGradient(
    colors = listOf(colors.blue, colors.purple),
)

/** 速度渐变（速度越快颜色越「热」） */
fun speedGradient(colors: AppColors): Brush = Brush.horizontalGradient(
    colors = listOf(colors.green, colors.blue),
)

/**
 * 渐变进度条：比系统 LinearProgressIndicator 更有速度感。
 *
 * [stalled] 为 true 时条会慢速呼吸，提示用户「不是界面死了，是连接卡住正在重建」。
 */
@Composable
fun GradientProgressBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 8.dp,
    tint: Brush? = null,
    stalled: Boolean = false,
) {
    val colors = AppTheme.colors
    val brush = tint ?: brandGradient(colors)
    val transition = rememberInfiniteTransition(label = "stallBreathe")
    val breatheAlpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breatheAlpha",
    )
    val safeFraction = fraction.coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(CircleShape)
            .background(colors.border.copy(alpha = 0.5f)),
    ) {
        // 用 fillMaxWidth(fraction) 而不是 Canvas：自带 RTL 正确行为，
        // 且 fraction 变化时由布局层做动画，不需要额外的手动插值。
        Box(
            modifier = Modifier
                .fillMaxWidth(safeFraction)
                .height(height)
                .clip(CircleShape)
                .background(brush)
                .alpha(if (stalled) breatheAlpha else 1f),
        )
    }
}

/**
 * 速度徽标：把「12.4 MB/s」做成一眼能读到重点的胶囊。
 * 数字用等宽字体，速度刷新时宽度不跳。
 */
@Composable
fun SpeedBadge(bytesPerSecond: Double, compact: Boolean = false) {
    val colors = AppTheme.colors
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(colors.green.copy(alpha = 0.12f))
            .padding(horizontal = if (compact) 7.dp else 9.dp, vertical = if (compact) 3.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            Icons.Filled.Bolt,
            contentDescription = null,
            tint = colors.green,
            modifier = Modifier.size(if (compact) 10.dp else 11.dp),
        )
        Text(
            text = formatSpeed(bytesPerSecond),
            fontSize = if (compact) 11.sp else 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = colors.green,
        )
    }
}

/**
 * 分段连接热力条：一眼看出上百条连接里哪几条在跑、哪几条卡住。
 * 每条用 2dp 宽的小竖条表示，颜色按状态走 —— 比文字列表直观得多。
 *
 * 按 `lanes.size` 均分宽度：条数少时每条更宽（更容易看出状态），
 * 条数上百时自动收窄成细线 —— 128 条连接也不会糊成一团。
 */
@Composable
fun LaneHeatStrip(lanes: List<LaneSnapshot>, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    if (lanes.isEmpty()) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(14.dp),
        horizontalArrangement = Arrangement.spacedBy(1.5.dp),
    ) {
        lanes.forEach { lane ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(stateColorOf(lane.state, colors)),
            )
        }
    }
}

/** 分段状态 → 语义色（热力条与明细行共用） */
fun stateColorOf(state: SegmentState, colors: AppColors): Color = when (state) {
    SegmentState.PENDING -> colors.border
    SegmentState.DOWNLOADING -> colors.green
    SegmentState.RETRYING -> colors.orange
    SegmentState.DONE -> colors.blue.copy(alpha = 0.7f)
    SegmentState.FAILED -> colors.red
}

/**
 * 圆角统计瓦片：把数字做大，标签做小 —— 信息密度和可读性兼顾。
 */
@Composable
fun MetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    icon: ImageVector? = null,
) {
    val colors = AppTheme.colors
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(colors.canvas.copy(alpha = 0.7f))
            .border(1.dp, colors.border.copy(alpha = 0.7f), MaterialTheme.shapes.small)
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = colors.subtle, modifier = Modifier.size(11.dp))
            }
            Text(label, fontSize = 10.sp, fontWeight = FontWeight.Medium, color = colors.subtle)
        }
        Text(
            value,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = tint ?: colors.strongText,
            maxLines = 1,
        )
    }
}

/**
 * 圆角卡片（带渐变描边）—— 用于「正在下载」这种需要一眼抓住注意力的容器。
 */
@Composable
fun GradientBorderCard(
    modifier: Modifier = Modifier,
    padding: androidx.compose.ui.unit.Dp = 14.dp,
    active: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = AppTheme.colors
    val brush = brandGradient(colors)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(colors.surface)
            .then(
                if (active) {
                    Modifier.border(1.6.dp, brush, MaterialTheme.shapes.medium)
                } else {
                    Modifier.border(1.dp, colors.border, MaterialTheme.shapes.medium)
                },
            )
            .padding(padding),
    ) {
        content()
    }
}

/**
 * 速度趋势迷你折线：把最近若干拍的速度画成一条细线。
 * 「速度在涨还是在掉」用文字看不出来，用一条线一秒钟就懂了。
 */
@Composable
fun SpeedSparkline(samples: List<Double>, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    if (samples.size < 2) return
    val maxValue = samples.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
    val brush = speedGradient(colors)

    androidx.compose.foundation.Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(18.dp),
    ) {
        val stepX = size.width / (samples.size - 1).coerceAtLeast(1)
        val path = androidx.compose.ui.graphics.Path()
        samples.forEachIndexed { index, value ->
            val x = index * stepX
            val y = size.height * (1f - (value / maxValue).toFloat()).coerceIn(0f, 1f)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            brush = brush,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 2.2f,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round,
            ),
        )
    }
}
