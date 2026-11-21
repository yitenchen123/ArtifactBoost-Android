package com.artifactboost.app.download

import java.io.File
import java.util.zip.CRC32
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

/**
 * 下载完成后的结构校验。
 *
 * 为什么需要：引擎保证的是「字节覆盖 + 长度一致」，这能挡住断流/漏段，
 * 但挡不住服务端给出一个长度正确、内容损坏的文件（镜像缓存坏了、
 * 代理截断后补齐、CDN 回源出错）。这类文件解压时才报错，
 * 用户体验就是「下完了却用不了」。
 *
 * Android 有 java.util.zip，所以这里做**完整 CRC 校验**（比 iOS 的结构校验更严），
 * 一般 SSD 上 500MB 也就一两秒。只对 zip / gz 生效，其他类型直接放行。
 */
object ArtifactVerifier {

    /** @return null 表示通过；否则是给用户看的原因 */
    fun verify(file: File): String? {
        val name = file.name.lowercase()
        return when {
            name.endsWith(".zip") -> verifyZip(file)
            name.endsWith(".tar.gz") || name.endsWith(".tgz") -> verifyGzip(file)
            else -> null
        }
    }

    private fun verifyZip(file: File): String? = try {
        ZipFile(file).use { zip ->
            val buffer = ByteArray(64 * 1024)
            for (entry in zip.entries()) {
                if (entry.isDirectory) continue
                val crc = CRC32()
                var total = 0L
                zip.getInputStream(entry).use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        crc.update(buffer, 0, read)
                        total += read
                    }
                }
                if (entry.size >= 0 && total != entry.size) {
                    return "ZIP 条目大小不符（${entry.name}）"
                }
                if (entry.crc >= 0 && crc.value != entry.crc) {
                    return "ZIP 条目 CRC 校验失败（${entry.name}）"
                }
            }
        }
        null
    } catch (e: Exception) {
        "ZIP 文件损坏：${e.message ?: "无法读取"}"
    }

    private fun verifyGzip(file: File): String? = try {
        // 读到流末尾时 GZIPInputStream 会校验 CRC32 与 ISIZE，不匹配会抛异常
        GZIPInputStream(file.inputStream().buffered(64 * 1024)).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                if (input.read(buffer) <= 0) break
            }
        }
        null
    } catch (e: Exception) {
        "压缩包损坏：${e.message ?: "无法解压"}"
    }
}
