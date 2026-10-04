package com.artifactboost.app.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * 下载完成后发布到安卓系统公共目录。
 *
 * 引擎本身仍先写 App 私有暂存目录（支持 RandomAccess 多线程乱序写盘，
 * 无需任何存储权限、卸载时可清理），完成后由这里复制一份到
 * 系统 `Download/ArtifactBoost/`：
 *
 * - Android 10+（API 29+，分区存储）：走 `MediaStore.Downloads` +
 *   `RELATIVE_PATH`，无需申请任何存储权限；
 * - Android 7~9（API 24~28）：直写
 *   `Environment.DIRECTORY_DOWNLOADS/ArtifactBoost/`，
 *   需 `WRITE_EXTERNAL_STORAGE`（Manifest 已按 maxSdkVersion=28 声明）。
 *
 * 发布失败返回 null，调用方保留私有文件兜底。
 */
data class PublishedFile(
    val uri: Uri,
    /** 展示用相对路径，例如 `Download/ArtifactBoost/xxx.zip` */
    val displayPath: String,
)

object PublicDownloads {

    const val SUBDIR = "ArtifactBoost"
    const val RELATIVE_DIR = "Download/$SUBDIR"

    /**
     * 把已下载好的暂存文件发布到系统公共下载目录。
     * 必须在 IO 线程调用（含文件复制）。
     */
    fun publish(context: Context, staged: File): PublishedFile? {
        if (!staged.exists() || !staged.isFile) return null
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                publishViaMediaStore(context, staged)
            } else {
                publishViaDirectFile(context, staged)
            }
        } catch (_: Exception) {
            null
        }
    }

    // MARK: - Android 10+：MediaStore

    private fun publishViaMediaStore(context: Context, staged: File): PublishedFile? {
        val resolver = context.applicationContext.contentResolver
        val mime = mimeTypeFor(staged.name)
        val fileName = uniquePublicName(resolver, staged.name)

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_DIR)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: return null
        try {
            resolver.openOutputStream(uri)?.use { out ->
                staged.inputStream().buffered().use { input -> input.copyTo(out) }
            } ?: run {
                runCatching { resolver.delete(uri, null, null) }
                return null
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        return PublishedFile(uri, "$RELATIVE_DIR/$fileName")
    }

    /** 同名文件已存在时加时间戳前缀，避免 MediaStore 出现重复条目。 */
    private fun uniquePublicName(
        resolver: android.content.ContentResolver,
        base: String,
    ): String {
        var candidate = base
        repeat(5) {
            if (!mediaStoreExists(resolver, candidate)) return candidate
            candidate = "${System.currentTimeMillis().toString().takeLast(6)}-$base"
        }
        return candidate
    }

    private fun mediaStoreExists(
        resolver: android.content.ContentResolver,
        displayName: String,
    ): Boolean {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return try {
            resolver.query(
                collection,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?",
                arrayOf(displayName, "$RELATIVE_DIR/"),
                null,
            )?.use { it.count > 0 } ?: false
        } catch (_: Exception) {
            false
        }
    }

    // MARK: - Android 7~9：直写公共 Download 目录

    @Suppress("DEPRECATION")
    private fun publishViaDirectFile(context: Context, staged: File): PublishedFile? {
        val publicDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            SUBDIR,
        )
        if (!publicDir.exists() && !publicDir.mkdirs()) return null
        var dest = File(publicDir, staged.name)
        if (dest.exists()) {
            dest = File(publicDir, "${System.currentTimeMillis().toString().takeLast(6)}-${staged.name}")
        }
        staged.inputStream().buffered().use { input ->
            dest.outputStream().buffered().use { output -> input.copyTo(output) }
        }
        MediaScannerConnection.scanFile(context.applicationContext, arrayOf(dest.absolutePath), null, null)
        return PublishedFile(Uri.fromFile(dest), "$RELATIVE_DIR/${dest.name}")
    }

    private fun mimeTypeFor(name: String): String = when {
        name.endsWith(".zip") -> "application/zip"
        name.endsWith(".tar.gz") || name.endsWith(".tgz") -> "application/gzip"
        name.endsWith(".apk") -> "application/vnd.android.package-archive"
        name.endsWith(".txt") || name.endsWith(".log") -> "text/plain"
        else -> "application/octet-stream"
    }
}
