package com.feiq.droid.core

import android.content.Context
import android.media.MediaScannerConnection
import java.io.File

/**
 * 把收到的图片登记进系统媒体库（MediaStore），相册才能看到。
 *
 * 背景：应用是用 `File` API 直接把图片写进公共目录的，这种方式**不会**自动进媒体库，
 * 所以文件管理器里能看到、相册里却没有。写完文件后调用 [indexImage] 补上登记即可。
 */
object MediaIndex {
    /**
     * 按文件头嗅探图片真实类型，返回 `扩展名 to MIME`。
     *
     * 必须这么做：飞秋内联图片原先固定存成 `.img`，而 MediaStore 主要靠扩展名判类型，
     * `.img` 认不出来就不会被索引，相册里自然没有。
     * 认不出的类型按 JPEG 兜底——Android 的解码器会自己嗅探内容，
     * 不会因为扩展名与实际格式不符就不显示。
     */
    fun sniff(bytes: ByteArray): Pair<String, String> {
        fun at(i: Int) = if (i < bytes.size) bytes[i].toInt() and 0xFF else -1
        return when {
            at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> "jpg" to "image/jpeg"
            at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> "png" to "image/png"
            at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 -> "gif" to "image/gif"
            at(0) == 0x42 && at(1) == 0x4D -> "bmp" to "image/bmp"
            at(8) == 0x57 && at(9) == 0x45 && at(10) == 0x42 && at(11) == 0x50 -> "webp" to "image/webp"
            else -> "jpg" to "image/jpeg"
        }
    }

    /** 把已写入磁盘的图片登记进媒体库。只对公共目录下的文件生效。 */
    fun indexImage(ctx: Context, file: File, mime: String) {
        try {
            if (!file.exists()) return
            // 未授权时文件落在应用私有目录，那里的图片不该出现在相册里
            if (!file.absolutePath.startsWith(Storage.publicRoot().absolutePath)) return
            MediaScannerConnection.scanFile(ctx, arrayOf(file.absolutePath), arrayOf(mime), null)
        } catch (_: Exception) {
        }
    }
}
