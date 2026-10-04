package com.artifactboost.app.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * 导出 / 分享已下载的文件。
 * 对应 iOS 的 ShareLink / UIActivityViewController。
 *
 * 下载完成后默认已保存到系统 `Download/ArtifactBoost/`（MediaStore 公共 Uri），
 * 优先直接分享该公共 Uri；发布失败兜底时才走 FileProvider 分享私有暂存文件。
 */
fun shareFile(context: Context, file: File) {
    if (!file.exists()) return
    try {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        shareUri(context, uri, file.name)
    } catch (e: Exception) {
        android.util.Log.w("ArtifactBoost", "分享失败", e)
    }
}

/** 分享已发布到系统公共下载目录的文件（MediaStore / 公共目录 Uri）。 */
fun sharePublicUri(context: Context, uri: Uri, fileName: String) {
    try {
        shareUri(context, uri, fileName)
    } catch (e: Exception) {
        android.util.Log.w("ArtifactBoost", "分享失败", e)
    }
}

private fun shareUri(context: Context, uri: Uri, fileName: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeTypeFor(fileName)
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, fileName)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(intent, "分享 / 打开文件").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(chooser)
}

private fun mimeTypeFor(name: String): String = when {
    name.endsWith(".zip") -> "application/zip"
    name.endsWith(".tar.gz") || name.endsWith(".tgz") -> "application/gzip"
    name.endsWith(".apk") -> "application/vnd.android.package-archive"
    name.endsWith(".ipa") -> "application/octet-stream"
    name.endsWith(".txt") || name.endsWith(".log") -> "text/plain"
    else -> "*/*"
}
