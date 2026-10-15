package io.github.kwebmn.rozklad.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import io.github.kwebmn.rozklad.data.remote.ReleaseInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

interface ApkDownloader {
    suspend fun download(release: ReleaseInfo): Boolean
}

class ApkInstaller(private val context: Context) : ApkDownloader {
    override suspend fun download(release: ReleaseInfo): Boolean = withContext(Dispatchers.IO) {
        val manager = context.getSystemService(DownloadManager::class.java) ?: return@withContext false
        apkFile().delete()
        val request = DownloadManager.Request(Uri.parse(release.apkUrl))
            .setTitle("Розклад ${release.version}")
            .setMimeType(MIME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, FILE_NAME)
        val id = manager.enqueue(request)
        var success = false
        try {
            var waited = 0L
            while (waited < TIMEOUT_MS) {
                delay(POLL_MS)
                waited += POLL_MS
                val status = manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                    if (!cursor.moveToFirst()) return@withContext false
                    cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                }
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        success = true
                        return@withContext true
                    }
                    DownloadManager.STATUS_FAILED -> return@withContext false
                }
            }
            false
        } finally {
            if (!success) manager.remove(id)
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
        const val POLL_MS = 1000L
        const val TIMEOUT_MS = 10 * 60 * 1000L
        const val FILE_NAME = "rozklad-update.apk"
        const val MIME = "application/vnd.android.package-archive"
    }
}
