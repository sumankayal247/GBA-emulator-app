package io.github.sumankayal247.gbaemulator.data.rom

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import android.Manifest

/**
 * Permission entry point for optional device-wide ROM discovery (ADR-0006).
 *
 * API 26-29 uses the normal runtime permission dialog. API 30+ has no general file-read
 * permission, so the user must grant All files access in system Settings; SAF remains available
 * when they decline.
 */
object StorageAccess {
    val legacyPermission: String = Manifest.permission.READ_EXTERNAL_STORAGE

    fun usesRuntimeDialog(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.R

    fun hasAccess(context: Context): Boolean {
        return if (usesRuntimeDialog()) {
            ContextCompat.checkSelfPermission(
                context,
                legacyPermission,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            Environment.isExternalStorageManager()
        }
    }

    fun allFilesAccessIntent(context: Context): Intent {
        val appUri = Uri.fromParts("package", context.packageName, null)
        val appSpecific = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, appUri)
        if (appSpecific.resolveActivity(context.packageManager) != null) return appSpecific
        return Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
    }
}
