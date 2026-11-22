package com.kongye.keyboard

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import java.io.File

internal enum class SmsSetupPermission(val title: String, private val markerName: String) {
    NOTIFICATION_SMS("通知类短信", "notification-sms"),
    CLIPBOARD("后台复制权限", "background-clipboard");

    private fun marker(context: Context) =
        File(context.noBackupFilesDir, "sms-permission-$markerName")

    fun isConfirmed(context: Context): Boolean = marker(context).isFile

    fun setConfirmed(context: Context, confirmed: Boolean): Boolean = runCatching {
        val file = marker(context)
        if (confirmed) file.createNewFile() else file.delete()
        file.isFile == confirmed
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    fun systemStatus(context: Context, guide: SmsSetupGuide): Boolean? {
        // The notification SMS setting is separate from Android's RECEIVE_SMS permission.
        if (this != CLIPBOARD || guide != SmsSetupGuide.XIAOMI || Build.VERSION.SDK_INT < 29) return null
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return null
        return runCatching {
            val operation = "android:write_clipboard"
            val uid = context.applicationInfo.uid
            val mode = if (Build.VERSION.SDK_INT >= 36) {
                ops.checkOpRawNoThrow(operation, uid, context.packageName, null)
            } else {
                ops.unsafeCheckOpRawNoThrow(operation, uid, context.packageName)
            }
            when (mode) {
                AppOpsManager.MODE_ALLOWED -> true
                AppOpsManager.MODE_FOREGROUND, AppOpsManager.MODE_IGNORED, AppOpsManager.MODE_ERRORED -> false
                else -> null
            }
        }.getOrNull()
    }
}
