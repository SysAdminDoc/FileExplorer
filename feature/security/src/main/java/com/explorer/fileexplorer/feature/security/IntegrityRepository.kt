package com.explorer.fileexplorer.feature.security

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.explorer.fileexplorer.core.database.IntegrityDao
import com.explorer.fileexplorer.core.database.IntegrityEntryEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

object IntegrityStatuses {
    const val OK = "OK"
    const val CHANGED = "CHANGED"
    const val MISSING = "MISSING"
    const val ERROR = "ERROR"

    fun isAlert(status: String): Boolean = status != OK
}

data class IntegrityScanSummary(
    val checked: Int,
    val changed: Int,
    val missing: Int,
    val errors: Int,
    val newlyAlerted: Int,
)

@Singleton
class IntegrityRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val integrityDao: IntegrityDao,
) {
    val entries: Flow<List<IntegrityEntryEntity>> = integrityDao.getAllFlow()

    suspend fun addPath(path: String): Result<IntegrityEntryEntity> = withContext(Dispatchers.IO) {
        try {
            val normalized = normalizePath(path)
            val fingerprint = IntegrityHasher.fingerprint(normalized).getOrThrow()
            val existing = integrityDao.getByPath(normalized)
            val entry = IntegrityEntryEntity(
                path = normalized,
                sha256 = fingerprint.sha256,
                size = fingerprint.size,
                modifiedAt = fingerprint.modifiedAt,
                isDirectory = fingerprint.isDirectory,
                addedAt = existing?.addedAt ?: System.currentTimeMillis(),
                lastCheckedAt = System.currentTimeMillis(),
                status = IntegrityStatuses.OK,
                lastError = null,
            )
            integrityDao.upsert(entry)
            schedulePeriodicWork()
            Result.success(entry)
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun removePath(path: String) = withContext(Dispatchers.IO) {
        val normalized = runCatching { normalizePath(path) }.getOrDefault(path)
        integrityDao.deleteByPath(normalized)
        if (integrityDao.getAll().isEmpty()) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    suspend fun scanNow(): Result<IntegrityScanSummary> = withContext(Dispatchers.IO) {
        try {
            val entries = integrityDao.getAll()
            var changed = 0
            var missing = 0
            var errors = 0
            var newlyAlerted = 0

            for (entry in entries) {
                val previousStatus = entry.status
                val checkedAt = System.currentTimeMillis()
                val updated = IntegrityHasher.fingerprint(entry.path).fold(
                    onSuccess = { fingerprint ->
                        val status = if (fingerprint.sha256.equals(entry.sha256, ignoreCase = true)) {
                            IntegrityStatuses.OK
                        } else {
                            IntegrityStatuses.CHANGED
                        }
                        if (status == IntegrityStatuses.CHANGED) changed++
                        if (IntegrityStatuses.isAlert(status) && !IntegrityStatuses.isAlert(previousStatus)) {
                            newlyAlerted++
                        }
                        entry.copy(
                            size = fingerprint.size,
                            modifiedAt = fingerprint.modifiedAt,
                            lastCheckedAt = checkedAt,
                            status = status,
                            lastError = null,
                        )
                    },
                    onFailure = { error ->
                        val status = if (File(entry.path).exists()) IntegrityStatuses.ERROR else IntegrityStatuses.MISSING
                        if (status == IntegrityStatuses.MISSING) missing++ else errors++
                        if (!IntegrityStatuses.isAlert(previousStatus)) newlyAlerted++
                        entry.copy(
                            lastCheckedAt = checkedAt,
                            status = status,
                            lastError = error.message,
                        )
                    },
                )
                integrityDao.upsert(updated)
            }

            Result.success(
                IntegrityScanSummary(
                    checked = entries.size,
                    changed = changed,
                    missing = missing,
                    errors = errors,
                    newlyAlerted = newlyAlerted,
                ),
            )
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private fun normalizePath(path: String): String {
        require(path.isNotBlank()) { "A path is required" }
        require('\u0000' !in path) { "Path cannot contain NUL" }
        return File(path.trim()).canonicalPath
    }

    private fun schedulePeriodicWork() {
        val request = PeriodicWorkRequestBuilder<IntegrityWorker>(SCAN_INTERVAL_MINUTES, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    private companion object {
        const val WORK_NAME = "file_integrity_periodic_scan"
        const val SCAN_INTERVAL_MINUTES = 15L
    }
}
