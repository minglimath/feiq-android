package com.feiq.droid.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/** 「所有文件访问」(MANAGE_EXTERNAL_STORAGE) 权限的跳转与状态判断。 */
object StoragePermission {
    /**
     * API 30+ 跳到本应用的「所有文件访问」设置页；返回 false 表示无法跳转
     * （例如部分定制 ROM），此时需要用户手动去系统设置开启。
     */
    fun openAllFilesAccessSettings(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return try {
            ctx.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${ctx.packageName}"),
                )
            )
            true
        } catch (_: Exception) {
            try {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                true
            } catch (_: Exception) {
                false
            }
        }
    }
}
