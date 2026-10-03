package com.feiq.droid.core

/**
 * 按文件头嗅探图片真实类型。
 *
 * 抽成独立文件（**不含任何 Android 依赖**）是为了让安卓端和 `:bridge` 网页桥共用同一份实现——
 * 两边都需要"给没有正确扩展名的图片定一个正确扩展名"。
 */
object ImageType {

    /**
     * 返回 `扩展名 to MIME`。
     *
     * 认不出的类型按 JPEG 兜底：Android 的解码器和浏览器的 `<img>` 都会自己嗅探内容，
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
}
