package com.artifactboost.app.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 字节数格式化：1048576 → 1.05 MB */
fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "0 B"
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.2f GB", gb)
}

/** 网速格式化 */
fun formatSpeed(bytesPerSecond: Double): String =
    formatBytes(maxOf(bytesPerSecond, 0.0).toLong()) + "/s"

/** 1200 → 1.2k，34000 → 34k（GitHub 的数字缩写风格） */
fun formatCount(count: Int): String = when {
    count < 1_000 -> count.toString()
    count < 10_000 -> String.format(Locale.US, "%.1fk", count / 1000.0)
    count < 1_000_000 -> "${count / 1000}k"
    else -> String.format(Locale.US, "%.1fm", count / 1_000_000.0)
}

/**
 * 相对时间：刚刚 / 5 分钟前 / 3 小时前 / 2 天前 / 4 个月前 / 1 年前
 * （对齐 GitHub 的时间展示风格，比绝对日期更符合移动端阅读习惯）
 */
fun formatRelative(millis: Long, nowMillis: Long = System.currentTimeMillis()): String {
    val interval = nowMillis - millis
    // 未来时间（时钟偏差）统一显示为「刚刚」，避免出现「-3 分钟前」
    if (interval < 0) return "刚刚"
    val seconds = interval / 1000
    if (seconds < 60) return "刚刚"

    val minutes = seconds / 60
    if (minutes < 60) return "$minutes 分钟前"

    val hours = minutes / 60
    if (hours < 24) return "$hours 小时前"

    val days = hours / 24
    if (days < 30) return "$days 天前"

    val months = days / 30
    if (months < 12) return "$months 个月前"

    return "${months / 12} 年前"
}

/** 绝对时间：2024-05-06 14:30 */
fun formatTimestamp(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

/**
 * 解析 GitHub 返回的 ISO8601 时间戳（例如 `2024-05-06T14:30:22Z`），失败返回 null。
 * 不用 java.time 是为了在 minSdk 24 上免去 desugaring 依赖。
 */
fun parseIso8601(raw: String?): Long? {
    if (raw.isNullOrBlank()) return null
    val patterns = arrayOf(
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
    )
    for (pattern in patterns) {
        try {
            val format = SimpleDateFormat(pattern, Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val parsed = format.parse(raw) ?: continue
            return parsed.time
        } catch (_: Exception) {
            // 换下一种格式继续试
        }
    }
    return null
}

/** 解析后直接格式化为相对时间，失败返回 null */
fun formatTimestamp(iso: String?): String? =
    parseIso8601(iso)?.let { formatTimestamp(it) }

/**
 * 发行版日期（对齐 GitHub 移动端）：
 * 一个月内显示相对时间并去掉「前」（如「4 小时」），更早显示「2026年8月24日」。
 */
fun formatReleaseDate(iso: String?): String? {
    val millis = parseIso8601(iso) ?: return null
    val interval = System.currentTimeMillis() - millis
    if (interval in 0 until 30L * 24 * 3600 * 1000) {
        return formatRelative(millis).removeSuffix("前")
    }
    return SimpleDateFormat("yyyy年M月d日", Locale.getDefault()).format(Date(millis))
}
