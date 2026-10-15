package io.github.kwebmn.rozklad.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import io.github.kwebmn.rozklad.data.remote.ReleaseInfo
import kotlinx.coroutines.delay
import java.io.File

interface ApkDownloader {
    suspend fun download(release: ReleaseInfo): Boolean
}

class ApkInstaller(private val context: Context) : ApkDownloader {
    override suspend fun download(release: ReleaseInfo): Boolean {
        val manager = context.getSystemService(DownloadManager::class.java)
        apkFile().delete()
        val request = DownloadManager.Request(Uri.parse(release.apkUrl))
            .setTitle("Розклад ${release.version}")
            .setMimeType(MIME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, FILE_NAME)
        val id = manager.enqueue(request)
        while (true) {
            delay(1000)
            manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                if (!cursor.moveToFirst()) return false
                when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                    DownloadManager.STATUS_SUCCESSFUL -> return true
                    DownloadManager.STATUS_FAILED -> return false
                }
            }
        }
    }

    /** Открывает системный установщик; если установка из этого источника запрещена — экран разрешения. */
    fun install() {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apkFile())
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, MIME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun apkFile() = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), FILE_NAME)

    private companion object {
        const val FILE_NAME = "rozklad-update.apk"
        const val MIME = "application/vnd.android.package-archive"
    }
}
