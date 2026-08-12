package com.explorer.fileexplorer.feature.transfer

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.explorer.fileexplorer.core.data.FileRepository
import com.explorer.fileexplorer.core.data.FileRepositoryFactory
import com.explorer.fileexplorer.core.data.UsbPathCodec
import com.explorer.fileexplorer.core.database.TransferTaskDao
import com.explorer.fileexplorer.core.model.ConflictNamePolicy
import com.explorer.fileexplorer.core.model.ConflictResolution
import com.explorer.fileexplorer.core.model.FileOperation
import com.explorer.fileexplorer.core.model.RepositoryOperation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransferQueueManager @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val repositoryFactory: FileRepositoryFactory,
    private val transferTaskDao: TransferTaskDao,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nextId = AtomicLong(System.currentTimeMillis())
    private val lock = Any()
    private val _tasks = MutableStateFlow<List<TransferQueueTask>>(emptyList())
    val tasks: StateFlow<List<TransferQueueTask>> = _tasks.asStateFlow()
    private val pauseSignals = mutableMapOf<Long, CompletableDeferred<Unit>>()
    private val conflictWaiters = mutableMapOf<Long, CompletableDeferred<TransferConflictAction>>()
    private val cancelledIds = mutableSetOf<Long>()
    private val persistenceMutex = Mutex()
    private val ready = CompletableDeferred<Unit>()
    private val lastPersistedAt = mutableMapOf<Long, Long>()
    private var runner: Job? = null
    private var activeId: Long? = null
    private var foregroundOwnershipRequested = false

    init {
        restorePersistedTasks()
    }

    fun enqueue(
        operation: FileOperation,
        sourcePaths: List<String>,
        destination: String,
        bandwidthLimitBytesPerSecond: Long = 0L,
        recoveryPolicy: TransferRecoveryPolicy = TransferRecoveryPolicy.ROLLBACK,
    ): Long {
        require(sourcePaths.isNotEmpty()) { "At least one source is required" }
        val id = nextId.incrementAndGet()
        val task = TransferQueueTask(
            id = id,
            idempotencyKey = "transfer-$id",
            operation = operation,
            sourcePaths = sourcePaths.distinct(),
            destination = destination,
            bandwidthLimitBytesPerSecond = bandwidthLimitBytesPerSecond.coerceAtLeast(0L),
            recoveryPolicy = recoveryPolicy,
        )
        _tasks.update {
            it + task
        }
        persistTask(task, force = true)
        startRunner()
        return id
    }

    fun pause(id: Long) {
        val task = tasks.value.firstOrNull { it.id == id } ?: return
        if (task.state !in setOf(TransferQueueState.QUEUED, TransferQueueState.RUNNING)) return
        updateTask(id) { it.copy(state = TransferQueueState.PAUSED) }
    }

    fun resume(id: Long) {
        val task = tasks.value.firstOrNull { it.id == id } ?: return
        if (task.state != TransferQueueState.PAUSED) return
        updateTask(id) {
            it.copy(state = if (activeId == id) TransferQueueState.RUNNING else TransferQueueState.QUEUED)
        }
        synchronized(lock) { pauseSignals.remove(id)?.complete(Unit) }
        startRunner()
    }

    fun retry(id: Long) {
        val task = tasks.value.firstOrNull { it.id == id } ?: return
        if (task.state != TransferQueueState.FAILED) return
        updateTask(id) {
            it.copy(
                state = TransferQueueState.QUEUED,
                error = null,
                conflict = null,
            )
        }
        startRunner()
    }

    fun cancel(id: Long) {
        val task = tasks.value.firstOrNull { it.id == id } ?: return
        if (task.isTerminal) return
        val wasActive = synchronized(lock) {
            cancelledIds += id
            conflictWaiters.remove(id)?.completeExceptionally(CancellationException("Transfer cancelled"))
            pauseSignals.remove(id)?.complete(Unit)
            activeId == id
        }
        updateTask(id) { it.copy(state = TransferQueueState.CANCELLED, conflict = null) }
        if (!wasActive) synchronized(lock) { cancelledIds.remove(id) }
        startRunner()
    }

    fun move(id: Long, offset: Int) {
        _tasks.update { list ->
            val index = list.indexOfFirst { it.id == id }
            val target = index + offset
            if (index < 0 || target !in list.indices) return@update list
            val mutable = list.toMutableList()
            val item = mutable.removeAt(index)
            mutable.add(target, item)
            mutable
        }
        persistAllTasks(force = true)
    }

    fun setBandwidthLimit(id: Long, bytesPerSecond: Long) {
        updateTask(id) {
            it.copy(bandwidthLimitBytesPerSecond = bytesPerSecond.coerceAtLeast(0L))
        }
    }

    fun resolveConflict(id: Long, action: TransferConflictAction, applyToAll: Boolean) {
        val task = tasks.value.firstOrNull { it.id == id } ?: return
        if (task.state != TransferQueueState.WAITING_CONFLICT || task.conflict == null) return
        val waiter = synchronized(lock) { conflictWaiters[id] } ?: return
        updateTask(id) {
            it.copy(
                state = TransferQueueState.RUNNING,
                conflict = null,
                conflictAction = if (applyToAll) action else it.conflictAction,
                applyConflictToAll = applyToAll,
                conflictDecisions = it.conflictDecisions + (task.conflict.sourcePath to action),
            )
        }
        waiter?.complete(action)
    }

    fun clearFinished() {
        val removedIds = tasks.value.filter { it.isTerminal }.map { it.id }
        _tasks.update { list -> list.filterNot { it.isTerminal } }
        deletePersisted(removedIds)
    }

    fun shutdown() {
        persistAllTasks(force = true)
        scope.coroutineContext[Job]?.cancel()
    }

    internal fun releaseForegroundOwnership() {
        synchronized(lock) { foregroundOwnershipRequested = false }
    }

    private fun startRunner() {
        requestForegroundOwnership()
        synchronized(lock) {
            if (runner?.isActive == true) return
            runner = scope.launch {
                try {
                    ready.await()
                    runQueue()
                } finally {
                    synchronized(lock) { runner = null }
                    if (tasks.value.any { it.state == TransferQueueState.QUEUED }) startRunner()
                }
            }
        }
    }

    private fun requestForegroundOwnership() {
        synchronized(lock) {
            if (foregroundOwnershipRequested) return
            foregroundOwnershipRequested = true
        }
        runCatching {
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, TransferService::class.java).setAction(TransferService.ACTION_MONITOR_QUEUE),
            )
        }.onFailure {
            synchronized(lock) { foregroundOwnershipRequested = false }
        }
    }

    private fun restorePersistedTasks() {
        scope.launch {
            try {
                val persisted = transferTaskDao.getAll()
                val restored = persisted.mapNotNull { entity ->
                    runCatching { entity.toTask() }
                        .onFailure { transferTaskDao.deleteById(entity.id) }
                        .getOrNull()
                }
                val recovered = restored.map(TransferQueueTask::recoverAfterProcessDeath)
                nextId.updateAndGet { current -> maxOf(current, recovered.maxOfOrNull { it.id } ?: current) }
                _tasks.update { current ->
                    val persistedIds = recovered.mapTo(hashSetOf()) { it.id }
                    recovered + current.filterNot { it.id in persistedIds }
                }
                recovered.filter { it.state == TransferQueueState.QUEUED }.forEach {
                    persistTask(it, force = true)
                }
            } finally {
                ready.complete(Unit)
                if (tasks.value.any { it.state == TransferQueueState.QUEUED }) startRunner()
            }
        }
    }

    internal suspend fun awaitReady() {
        ready.await()
    }

    private fun persistTask(task: TransferQueueTask, force: Boolean = false) {
        val now = System.nanoTime()
        val terminal = task.isTerminal || task.state == TransferQueueState.PAUSED ||
            task.state == TransferQueueState.CANCELLED || task.state == TransferQueueState.WAITING_CONFLICT
        synchronized(lock) {
            val previous = lastPersistedAt[task.id] ?: 0L
            if (!force && !terminal && now - previous < PERSIST_INTERVAL_NANOS) return
            lastPersistedAt[task.id] = now
        }
        scope.launch {
            runCatching {
                ready.await()
                persistenceMutex.withLock {
                    val latest = tasks.value.firstOrNull { it.id == task.id } ?: return@withLock
                    val order = tasks.value.indexOfFirst { it.id == latest.id }.coerceAtLeast(0)
                    transferTaskDao.upsert(latest.toEntity(order))
                }
            }
        }
    }

    private fun persistAllTasks(force: Boolean) {
        tasks.value.forEach { persistTask(it, force) }
    }

    private fun deletePersisted(ids: List<Long>) {
        if (ids.isEmpty()) return
        scope.launch {
            runCatching {
                ready.await()
                persistenceMutex.withLock {
                    ids.forEach { transferTaskDao.deleteById(it) }
                }
            }
        }
    }

    private suspend fun runQueue() {
        while (true) {
            val task = tasks.value.firstOrNull { it.state == TransferQueueState.QUEUED } ?: return
            activeId = task.id
            execute(task)
            activeId = null
        }
    }

    private suspend fun execute(initial: TransferQueueTask) {
        val taskId = initial.id
        var started = false
        _tasks.update { list ->
            list.map {
                if (it.id == taskId && it.state == TransferQueueState.QUEUED) {
                    started = true
                    it.copy(state = TransferQueueState.RUNNING, error = null)
                } else it
            }
        }
        if (!started) return
        var transferredBefore = initial.transferredBytes.coerceAtLeast(0L)
        var sharedAction = initial.conflictAction

        try {
            val repository = if (initial.operation == FileOperation.DELETE) {
                repositoryFactory.getRepository(initial.sourcePaths.first())
            } else {
                repositoryFactory.getTransferRepository(initial.sourcePaths, initial.destination)
            }
            val totalBytes = if (initial.operation == FileOperation.DELETE) 0L else repository.calculateSize(initial.sourcePaths)
            val intendedEntries = initial.intendedEntries.ifEmpty {
                initial.sourcePaths.map { source ->
                    TransferJournalEntry(
                        sourcePath = source,
                        destinationPath = if (initial.operationNeedsDestination()) {
                            targetPath(initial.destination, source)
                        } else {
                            source
                        },
                    )
                }
            }
            updateTask(taskId) {
                it.copy(
                    totalBytes = totalBytes,
                    intendedEntries = intendedEntries,
                    recoveryPolicy = initial.recoveryPolicy,
                )
            }
            persistTaskNow(taskId)
            val completedBefore = initial.completedSources.coerceIn(0, initial.sourcePaths.size)
            initial.sourcePaths.drop(completedBefore).forEachIndexed { offset, source ->
                val index = completedBefore + offset
                awaitRunnable(taskId)
                ensureNotCancelled(taskId)
                val task = tasks.value.first { it.id == taskId }
                val target = targetPath(task.destination, source)
                val sourceSize = if (task.operationNeedsDestination()) {
                    runCatching { repository.calculateSize(listOf(source)) }.getOrDefault(0L)
                } else 0L
                val targetExists = task.operationNeedsDestination() && repository.exists(target)
                val conflict = if (targetExists) {
                    buildConflict(repository, source, target, sourceSize, task.idempotencyKey)
                } else null
                if (targetExists && matchesDelivery(repository, source, target, task.operation)) {
                    transferredBefore += sourceSize
                    updateTask(taskId) {
                        it.copy(
                            transferredBytes = transferredBefore,
                            completedSources = index + 1,
                            currentFile = source,
                            committedEntries = it.committedEntries.recordJournal(source, TransferJournalState.COMMITTED),
                        )
                    }
                    persistTaskNow(taskId)
                    return@forEachIndexed
                }
                val action = if (conflict == null) null else {
                    sharedAction
                        ?: tasks.value.first { it.id == taskId }.conflictDecisions[source]
                        ?: requestConflict(taskId, conflict)
                }
                if (action != null && tasks.value.first { it.id == taskId }.applyConflictToAll) sharedAction = action
                if (action == TransferConflictAction.SKIP) {
                    updateTask(taskId) {
                        it.copy(
                            completedSources = index + 1,
                            currentFile = source,
                            committedEntries = it.committedEntries.recordJournal(source, TransferJournalState.SKIPPED),
                        )
                    }
                    persistTaskNow(taskId)
                    return@forEachIndexed
                }

                val conflictSuffix = if (action == TransferConflictAction.KEEP_BOTH && conflict != null) {
                    ConflictNamePolicy.suffix(task.idempotencyKey, source, target)
                } else {
                    null
                }
                if (action == TransferConflictAction.KEEP_BOTH && conflict != null &&
                    alreadyDelivered(repository, conflict, task.operation)
                ) {
                    transferredBefore += sourceSize
                    updateTask(taskId) {
                        it.copy(
                            transferredBytes = transferredBefore,
                            completedSources = index + 1,
                            currentFile = source,
                            committedEntries = it.committedEntries.recordJournal(source, TransferJournalState.COMMITTED),
                        )
                    }
                    persistTaskNow(taskId)
                    return@forEachIndexed
                }

                val resolution = when (action) {
                    TransferConflictAction.REPLACE -> ConflictResolution.OVERWRITE
                    TransferConflictAction.RENAME, TransferConflictAction.KEEP_BOTH, null -> ConflictResolution.RENAME
                    TransferConflictAction.SKIP -> ConflictResolution.SKIP
                }
                val limiter = BandwidthLimiter(tasks.value.first { it.id == taskId }.bandwidthLimitBytesPerSecond)
                val result = when (task.operation) {
                    FileOperation.COPY -> repository.copyFiles(
                        sources = listOf(source),
                        destination = task.destination,
                        conflictResolution = resolution,
                        conflictSuffix = conflictSuffix,
                    ) { copied, _, file ->
                        ensureNotCancelled(taskId)
                        limiter.throttle(copied)
                        updateTask(taskId) {
                            it.copy(
                                transferredBytes = transferredBefore + copied,
                                currentFile = file,
                                state = if (it.state == TransferQueueState.PAUSED) TransferQueueState.PAUSED else TransferQueueState.RUNNING,
                            )
                        }
                    }
                    FileOperation.MOVE -> repository.moveFiles(
                        sources = listOf(source),
                        destination = task.destination,
                        conflictResolution = resolution,
                        conflictSuffix = conflictSuffix,
                    ) { moved, _, file ->
                        ensureNotCancelled(taskId)
                        limiter.throttle(moved)
                        updateTask(taskId) {
                            it.copy(
                                transferredBytes = transferredBefore + moved,
                                currentFile = file,
                                state = if (it.state == TransferQueueState.PAUSED) TransferQueueState.PAUSED else TransferQueueState.RUNNING,
                            )
                        }
                    }
                    FileOperation.DELETE -> repository.deleteFiles(listOf(source)) { file ->
                        ensureNotCancelled(taskId)
                        updateTask(taskId) { it.copy(currentFile = file, state = TransferQueueState.RUNNING) }
                    }
                    else -> Result.failure(UnsupportedOperationException("Unsupported transfer operation"))
                }
                ensureNotCancelled(taskId)
                val resultCount = result.getOrThrow()
                require(resultCount > 0) { "Transfer provider committed no entries for $source" }
                transferredBefore += sourceSize
                updateTask(taskId) {
                    it.copy(
                        transferredBytes = transferredBefore,
                        completedSources = index + 1,
                        committedEntries = it.committedEntries.recordJournal(source, TransferJournalState.COMMITTED),
                    )
                }
                persistTaskNow(taskId)
            }
            updateTask(taskId) { it.copy(state = TransferQueueState.COMPLETED, conflict = null) }
        } catch (cancelled: CancellationException) {
            if (isCancelled(taskId)) {
                updateTask(taskId) {
                    it.copy(
                        state = TransferQueueState.CANCELLED,
                        conflict = null,
                        error = it.partialJournalMessage("Transfer cancelled") ?: "Transfer cancelled",
                    )
                }
            } else {
                throw cancelled
            }
        } catch (error: Exception) {
            updateTask(taskId) {
                it.copy(
                    state = TransferQueueState.FAILED,
                    error = it.partialJournalMessage(error.message ?: error::class.simpleName),
                    retryCount = it.retryCount + 1,
                    conflict = null,
                )
            }
        } finally {
            synchronized(lock) {
                cancelledIds.remove(taskId)
                pauseSignals.remove(taskId)
                conflictWaiters.remove(taskId)
            }
        }
    }

    private suspend fun requestConflict(id: Long, conflict: TransferConflict): TransferConflictAction {
        val waiter = CompletableDeferred<TransferConflictAction>()
        synchronized(lock) { conflictWaiters[id] = waiter }
        updateTask(id) { it.copy(state = TransferQueueState.WAITING_CONFLICT, conflict = conflict) }
        return try {
            waiter.await()
        } finally {
            synchronized(lock) { conflictWaiters.remove(id) }
        }
    }

    private suspend fun awaitRunnable(id: Long) {
        while (true) {
            ensureNotCancelled(id)
            if (tasks.value.firstOrNull { it.id == id }?.state != TransferQueueState.PAUSED) return
            val signal = CompletableDeferred<Unit>()
            synchronized(lock) { pauseSignals[id] = signal }
            signal.await()
        }
    }

    private fun updateTask(id: Long, transform: (TransferQueueTask) -> TransferQueueTask) {
        var updated: TransferQueueTask? = null
        _tasks.update { list ->
            list.map {
                if (it.id == id) transform(it).also { task -> updated = task } else it
            }
        }
        updated?.let { persistTask(it) }
    }

    private suspend fun persistTaskNow(id: Long) {
        ready.await()
        persistenceMutex.withLock {
            val latest = tasks.value.firstOrNull { it.id == id } ?: return
            val order = tasks.value.indexOfFirst { it.id == latest.id }.coerceAtLeast(0)
            transferTaskDao.upsert(latest.toEntity(order))
        }
    }

    private fun ensureNotCancelled(id: Long) {
        if (isCancelled(id)) throw CancellationException("Transfer cancelled")
    }

    private fun isCancelled(id: Long): Boolean = synchronized(lock) { id in cancelledIds }

    private fun TransferQueueTask.operationNeedsDestination(): Boolean =
        operation == FileOperation.COPY || operation == FileOperation.MOVE

    private fun List<TransferJournalEntry>.recordJournal(
        sourcePath: String,
        state: TransferJournalState,
    ): List<TransferJournalEntry> {
        val intended = firstOrNull { it.sourcePath == sourcePath } ?: return this
        return filterNot { it.sourcePath == sourcePath } + intended.copy(state = state)
    }

    private fun TransferQueueTask.partialJournalMessage(detail: String? = null): String? {
        val committed = committedEntries.size
        val intended = intendedEntries.size
        if (committed == 0 && detail.isNullOrBlank()) return null
        val prefix = if (intended > committed) "Partial completion ($committed/$intended committed)" else "Operation interrupted"
        return listOfNotNull(prefix, detail).joinToString(": ")
    }

    private fun targetPath(destination: String, source: String): String {
        val name = if (UsbPathCodec.isUsbPath(source)) {
            UsbPathCodec.name(source) ?: ""
        } else {
            source.trimEnd('/', '\\').substringAfterLastAny('/', '\\')
        }
        return if (UsbPathCodec.isUsbPath(destination)) {
            UsbPathCodec.childPath(destination, name)
        } else {
            destination.trimEnd('/', '\\') + "/" + name
        }
    }

    private fun deterministicTargetPath(target: String, suffix: String): String {
        if (UsbPathCodec.isUsbPath(target)) {
            val parent = UsbPathCodec.parentPath(target) ?: return target
            val name = UsbPathCodec.name(target) ?: return target
            return UsbPathCodec.childPath(parent, ConflictNamePolicy.fileName(name, suffix))
        }
        return ConflictNamePolicy.pathWithName(target, suffix)
    }

    private suspend fun buildConflict(
        repository: FileRepository,
        source: String,
        target: String,
        sourceSize: Long,
        operationKey: String,
    ): TransferConflict {
        val sourceInfo = runCatching { repository.getFileInfo(source) }.getOrNull()
        val destinationInfo = runCatching { repository.getFileInfo(target) }.getOrNull()
        val extension = source.substringAfterLast('.', "").lowercase()
        val isText = sourceInfo?.isText ?: extension in TEXT_EXTENSIONS
        val diff = if (isText && !source.contains("://") && !target.contains("://")) {
            textDiff(source, target)
        } else ""
        return TransferConflict(
            sourcePath = source,
            destinationPath = target,
            isText = isText,
            diffPreview = diff,
            sourceSize = sourceInfo?.size ?: sourceSize,
            destinationSize = destinationInfo?.size,
            sourceModified = sourceInfo?.lastModified,
            destinationModified = destinationInfo?.lastModified,
            sourceIsDirectory = sourceInfo?.isDirectory == true,
            destinationIsDirectory = destinationInfo?.isDirectory == true,
            plannedKeepBothPath = deterministicTargetPath(
                target,
                ConflictNamePolicy.suffix(operationKey, source, target),
            ),
        )
    }

    private suspend fun alreadyDelivered(
        repository: FileRepository,
        conflict: TransferConflict,
        operation: FileOperation,
    ): Boolean {
        val candidatePath = conflict.plannedKeepBothPath ?: return false
        return matchesDelivery(repository, conflict.sourcePath, candidatePath, operation)
    }

    private suspend fun matchesDelivery(
        repository: FileRepository,
        sourcePath: String,
        destinationPath: String,
        operation: FileOperation,
    ): Boolean {
        val candidate = runCatching { repository.getFileInfo(destinationPath) }.getOrNull() ?: return false
        val source = runCatching { repository.getFileInfo(sourcePath) }.getOrNull()
        if (source == null) return operation == FileOperation.MOVE
        if (source.isDirectory != candidate.isDirectory) return false
        if (source.isDirectory) return true
        if (source.size != candidate.size) return false
        if (repository.capabilities.supports(RepositoryOperation.CHECKSUM) &&
            source.size <= 2L * 1024L * 1024L * 1024L
        ) {
            return runCatching {
                repository.getChecksum(sourcePath) == repository.getChecksum(destinationPath)
            }.getOrDefault(true)
        }
        return true
    }

    private fun textDiff(source: String, target: String): String {
        return try {
            val left = readPreview(Paths.get(source))
            val right = readPreview(Paths.get(target))
            if (left == right) {
                "Text contents are identical in the preview."
            } else {
                val leftLines = left.lines()
                val rightLines = right.lines()
                buildString {
                    appendLine("--- existing destination")
                    appendLine("+++ incoming source")
                    leftLines.zipLongest(rightLines).take(MAX_DIFF_LINES).forEach { (old, new) ->
                        if (old != new) {
                            old?.let { appendLine("- $it") }
                            new?.let { appendLine("+ $it") }
                        }
                    }
                }.trimEnd()
            }
        } catch (_: IOException) {
            "Text diff unavailable for this file."
        }
    }

    private fun readPreview(path: Path): String = Files.newInputStream(path).use { input ->
        val bytes = ByteArray(MAX_DIFF_BYTES)
        var count = 0
        while (count < bytes.size) {
            val read = input.read(bytes, count, bytes.size - count)
            if (read <= 0) break
            count += read
        }
        String(bytes, 0, count, StandardCharsets.UTF_8)
    }

    private class BandwidthLimiter(private val limit: Long) {
        private var lastBytes = 0L
        private var lastNanos = System.nanoTime()

        fun throttle(totalBytes: Long) {
            if (limit <= 0L || totalBytes <= lastBytes) return
            val delta = totalBytes - lastBytes
            val targetNanos = delta * 1_000_000_000L / limit
            val elapsed = System.nanoTime() - lastNanos
            if (targetNanos > elapsed) {
                val sleepNanos = (targetNanos - elapsed).coerceAtMost(1_000_000_000L)
                try {
                    Thread.sleep(sleepNanos / 1_000_000L, (sleepNanos % 1_000_000L).toInt())
                } catch (_: InterruptedException) {
                    throw CancellationException("Transfer interrupted")
                }
            }
            lastBytes = totalBytes
            lastNanos = System.nanoTime()
        }
    }

    private companion object {
        const val PERSIST_INTERVAL_NANOS = 250_000_000L
        const val MAX_DIFF_BYTES = 64 * 1024
        const val MAX_DIFF_LINES = 80
        val TEXT_EXTENSIONS = setOf("txt", "md", "json", "xml", "yaml", "yml", "toml", "ini", "cfg", "conf", "log", "csv", "tsv", "kt", "java", "js", "ts", "html", "css", "py", "rs", "go", "sql")
    }
}

private fun String.substringAfterLastAny(first: Char, second: Char): String {
    val index = lastIndexOfAny(charArrayOf(first, second))
    return if (index >= 0) substring(index + 1) else this
}

private fun <A, B> List<A>.zipLongest(other: List<B>): List<Pair<A?, B?>> =
    (0 until maxOf(size, other.size)).map { index -> getOrNull(index) to other.getOrNull(index) }
