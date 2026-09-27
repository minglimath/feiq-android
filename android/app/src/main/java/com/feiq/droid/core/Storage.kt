package com.feiq.droid.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import java.io.File

/**
 * 统一存储目录。
 *
 * 默认写入公共目录 `Download/FeiQ`，文件管理器和数据线可直接查看。
 * 未授予「所有文件访问」(MANAGE_EXTERNAL_STORAGE) 时自动回退到应用私有目录，
 * 保证功能始终可用（此时文件对其它应用不可见）。
 */
object Storage {
    private const val ROOT_NAME = "FeiQ"

    /** 是否已获得对公共存储的完整读写能力。 */
    fun hasAllFilesAccess(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }

    /** 公共存储根目录 `Download/FeiQ`。 */
    @Suppress("DEPRECATION")
    fun publicRoot(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        ROOT_NAME,
    )

    /** 权限不足时的回退根目录（应用外部私有目录）。 */
    private fun fallbackRoot(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, ROOT_NAME)

    fun root(ctx: Context): File {
        val dir = if (hasAllFilesAccess(ctx)) publicRoot() else fallbackRoot(ctx)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun receivedDir(ctx: Context) = sub(ctx, "received")
    fun imagesDir(ctx: Context) = sub(ctx, "images")
    fun avatarsDir(ctx: Context) = sub(ctx, "avatars")
    fun chatDir(ctx: Context) = sub(ctx, "chat")
    fun backupDir(ctx: Context) = sub(ctx, "backup")

    private fun sub(ctx: Context, name: String): File =
        File(root(ctx), name).apply { if (!exists()) mkdirs() }

    /**
     * 旧版本使用的内部/应用外部目录 → 新目录的映射，仅用于一次性迁移。
     * 迁移只做拷贝、不删除源目录，因此旧记录里的绝对路径仍然有效。
     */
    private fun legacyPairs(ctx: Context): List<Pair<File, File>> = listOfNotNull(
        File(ctx.filesDir, "chat") to chatDir(ctx),
        File(ctx.filesDir, "images") to imagesDir(ctx),
        File(ctx.filesDir, "avatars") to avatarsDir(ctx),
        ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { File(it, "received") to receivedDir(ctx) },
        ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)?.let { File(it, "backup") to backupDir(ctx) },
    )

    /** 首次获得权限后把旧目录的数据拷贝到新目录。可重复调用，成功一次后不再执行。 */
    fun migrateLegacy(ctx: Context) {
        if (!hasAllFilesAccess(ctx)) return
        if (Prefs.isStorageMigrated(ctx)) return
        var ok = true
        legacyPairs(ctx).forEach { (src, dst) ->
            try {
                if (src.exists() && src.absolutePath != dst.absolutePath && !copyTree(src, dst)) ok = false
            } catch (_: Exception) {
                ok = false
            }
        }
        if (ok) Prefs.setStorageMigrated(ctx, true)
    }

    private fun copyTree(src: File, dst: File): Boolean {
        if (src.isDirectory) {
            if (!dst.exists() && !dst.mkdirs()) return false
            src.listFiles()?.forEach { child ->
                if (!copyTree(child, File(dst, child.name))) return false
            }
            return true
        }
        return try {
            dst.parentFile?.mkdirs()
            src.inputStream().use { input -> dst.outputStream().use { input.copyTo(it) } }
            true
        } catch (_: Exception) {
            false
        }
    }
}
