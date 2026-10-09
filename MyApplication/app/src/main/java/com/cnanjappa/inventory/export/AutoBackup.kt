package com.cnanjappa.inventory.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cnanjappa.inventory.container
import com.cnanjappa.inventory.data.InventoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * Nightly automatic backup, no questions asked. Around [HOUR]:00 each night, if stock or history
 * changed since the last backup, a checked backup is written to Download/[FOLDER] (visible in the
 * Files app and kept if the app is uninstalled). Keeps the newest [KEEP] files. A night the phone
 * was off is caught up as soon as it can run.
 */
object AutoBackup {
    const val FOLDER = "CNCC Backups"
    const val HOUR = 23
    private const val KEEP = 14
    private const val WORK = "nightly-backup"

    fun schedule(context: Context) {
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(LocalTime.of(HOUR, 0))
        if (!next.isAfter(now)) next = next.plusDays(1)
        val request = PeriodicWorkRequestBuilder<Worker>(24, TimeUnit.HOURS)
            .setInitialDelay(Duration.between(now, next).toMinutes(), TimeUnit.MINUTES)
            .build()
        // KEEP: reopening the app never pushes tonight's run further away.
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Where the files go, in words staff can follow in the Files app. */
    fun locationText() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "Files › Download › $FOLDER" else "Android/data/…/files/$FOLDER"

    /** Backs up if anything changed since the last backup. Returns false when there was nothing to do. */
    suspend fun runIfChanged(context: Context): Boolean {
        val c = context.container
        val last = c.repo.dao.setting(InventoryRepository.KEY_LAST_BACKUP)?.toLongOrNull() ?: 0L
        val change = c.repo.dao.lastChange().first()
        if (change == null || change <= last) return false
        val name = "cncc-backup-${LocalDate.now()}.cncbak"
        val info = withContext(Dispatchers.IO) {
            val (uri, finish) = createFile(context, name)
            try {
                c.backup().backupTo(uri).also { finish(true) }
            } catch (e: Exception) {
                finish(false); throw e
            }
        }
        c.repo.setSetting(InventoryRepository.KEY_LAST_BACKUP, info.createdAt.toString())
        prune(context)
        return true
    }

    /** A new file in the backup folder, plus a callback that publishes it (true) or removes it (false). */
    private fun createFile(context: Context, name: String): Pair<Uri, (Boolean) -> Unit> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            ownFiles(context).filter { it.second == name }.forEach { resolver.delete(it.first, null, null) }  // rerun the same day
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw BackupException("Could not create the backup file")
            return uri to { ok ->
                if (ok) resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                else resolver.delete(uri, null, null)
            }
        }
        // Android 8–9: shared Downloads needs a storage permission, so use the app's own folder.
        val file = File(legacyDir(context), name)
        return file.toUri() to { ok -> if (!ok) file.delete() }
    }

    private fun prune(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ownFiles(context).sortedByDescending { it.second }.drop(KEEP).forEach { context.contentResolver.delete(it.first, null, null) }
        } else {
            legacyDir(context).listFiles { f -> f.name.endsWith(".cncbak") }?.sortedByDescending { it.name }?.drop(KEEP)?.forEach { it.delete() }
        }
    }

    /** Backups this install wrote (Android only lists an app's own files without a storage permission). */
    internal fun ownFiles(context: Context): List<Pair<Uri, String>> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        val out = mutableListOf<Pair<Uri, String>>()
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME),
            "${MediaStore.Downloads.RELATIVE_PATH} = ? AND ${MediaStore.Downloads.DISPLAY_NAME} LIKE 'cncc-backup-%'",
            arrayOf("${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/"), null,
        )?.use { c ->
            while (c.moveToNext()) out += Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0).toString()) to c.getString(1)
        }
        return out
    }

    private fun legacyDir(context: Context) = File(context.getExternalFilesDir(null), FOLDER).apply { mkdirs() }

    class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result = try {
            runIfChanged(applicationContext)
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("AutoBackup", "Nightly backup failed", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
