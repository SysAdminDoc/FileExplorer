package com.explorer.fileexplorer.core.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.PriorityQueue
import kotlin.math.min
import javax.inject.Inject
import javax.inject.Singleton

data class StorageEntry(
    val name: String,
    val path: String,
    val size: Long,
    val modifiedAt: Long = 0L,
)

data class DuplicateGroup(
    val size: Long,
    val files: List<StorageEntry>,
)

data class StorageTreeNode(
    val name: String,
    val path: String,
    val size: Long,
    val fileCount: Int,
    val isDirectory: Boolean,
    val isAggregate: Boolean = false,
    val children: List<StorageTreeNode> = emptyList(),
)

enum class StorageScanPhase {
    INDEXING,
    HASHING,
}

data class StorageScanProgress(
    val files: Int = 0,
    val directories: Int = 0,
    val phase: StorageScanPhase = StorageScanPhase.INDEXING,
    val skippedFiles: Int = 0,
    val hashBytesRead: Long = 0L,
    val isResuming: Boolean = false,
)

/** Safety limits for an analyzer run. No file contents are retained by the analyzer. */
data class StorageScanLimits(
    val maxFiles: Int = 50_000,
    val maxDirectories: Int = 25_000,
    val maxIndexedBytes: Long = 50L * 1024L * 1024L * 1024L,
    val maxHashBytes: Long = 1L * 1024L * 1024L * 1024L,
    val maxHashCandidates: Int = 20_000,
    val sampleBytes: Int = 64 * 1024,
) {
    init {
        require(maxFiles > 0)
        require(maxDirectories > 0)
        require(maxIndexedBytes > 0)
        require(maxHashBytes > 0)
        require(maxHashCandidates > 0)
        require(sampleBytes > 0)
    }

    companion object {
        val DEFAULT = StorageScanLimits()
    }
}

/** Metadata-only checkpoint. It never contains file contents, only paths, sizes, and hashes. */
data class StorageScanCheckpoint(
    val rootPath: String,
    val files: List<StorageEntry>,
    val directories: List<String>,
    val sampleHashes: Map<String, String> = emptyMap(),
    val fullHashes: Map<String, String> = emptyMap(),
    val hashBytesRead: Long = 0L,
    val hashCandidates: Int = 0,
    val skippedFiles: Int = 0,
)

data class StorageScanResult(
    val root: StorageTreeNode,
    val totalBytes: Long,
    val fileCount: Int,
    val directoryCount: Int,
    val largestFiles: List<StorageEntry>,
    val duplicateGroups: List<DuplicateGroup>,
    val isComplete: Boolean = true,
    val hashAnalysisComplete: Boolean = true,
    val skippedFiles: Int = 0,
    val inaccessibleEntries: Int = 0,
    val hashBytesRead: Long = 0L,
    val hashCandidates: Int = 0,
    val checkpoint: StorageScanCheckpoint? = null,
)

@Singleton
class StorageAnalyzer @Inject constructor() {

    suspend fun scan(
        rootPath: String,
        onProgress: (StorageScanProgress) -> Unit = {},
        resumeFrom: StorageScanCheckpoint? = null,
        limits: StorageScanLimits = StorageScanLimits.DEFAULT,
        onCheckpoint: (StorageScanCheckpoint) -> Unit = {},
    ): StorageScanResult = withContext(Dispatchers.IO) {
        val scanContext = currentCoroutineContext()
        val root = Paths.get(rootPath).toAbsolutePath().normalize()
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) { "Not a directory: $rootPath" }

        val validCheckpoint = resumeFrom?.takeIf { checkpoint ->
            normalizePath(checkpoint.rootPath) == root.toString() &&
                checkpoint.files.size <= limits.maxFiles &&
                checkpoint.directories.size <= limits.maxDirectories
        }
        val stableEntries = validCheckpoint?.files
            ?.asSequence()
            ?.mapNotNull { entry -> stableEntry(entry, root) }
            ?.distinctBy { it.path }
            ?.take(limits.maxFiles)
            ?.toList()
            .orEmpty()
        val stablePaths = stableEntries.mapTo(hashSetOf()) { it.path }
        val stableDirectories = validCheckpoint?.directories
            ?.asSequence()
            ?.map { normalizePath(it) }
            ?.filter { it != root.toString() && isWithinRoot(Paths.get(it), root) }
            ?.filter { Files.isDirectory(Paths.get(it), LinkOption.NOFOLLOW_LINKS) }
            ?.distinct()
            ?.take(limits.maxDirectories)
            ?.toCollection(LinkedHashSet())
            ?: linkedSetOf()

        val tree = MutableNode(name = root.fileName?.toString() ?: root.toString(), path = root.toString())
        val entriesByPath = linkedMapOf<String, StorageEntry>()
        val candidatesBySize = mutableMapOf<Long, MutableList<StorageEntry>>()
        val largest = PriorityQueue<StorageEntry>(compareBy { it.size })
        val directories = LinkedHashSet<String>().apply { addAll(stableDirectories) }
        var fileCount = 0
        var directoryCount = directories.size
        var skippedFiles = validCheckpoint?.skippedFiles ?: 0
        var inaccessibleEntries = 0
        var bounded = false

        fun addEntry(entry: StorageEntry) {
            entriesByPath[entry.path] = entry
            fileCount++
            addToTree(tree, root, Paths.get(entry.path), entry.size)
            candidatesBySize.getOrPut(entry.size) { mutableListOf() }.add(entry)
            largest.add(entry)
            if (largest.size > MAX_LARGEST_FILES) largest.poll()
        }

        stableDirectories.forEach { directory -> findOrCreateNode(tree, root, Paths.get(directory), isDirectory = true) }
        stableEntries.forEach(::addEntry)

        var hashBytesRead = validCheckpoint?.hashBytesRead?.coerceAtLeast(0L) ?: 0L
        var hashCandidates = validCheckpoint?.hashCandidates?.coerceAtLeast(0) ?: 0
        val sampleHashes = validCheckpoint?.sampleHashes
            ?.filterKeys { normalizePath(it) in stablePaths }
            ?.mapKeys { normalizePath(it.key) }
            ?.toMutableMap()
            ?: mutableMapOf()
        val fullHashes = validCheckpoint?.fullHashes
            ?.filterKeys { normalizePath(it) in stablePaths }
            ?.mapKeys { normalizePath(it.key) }
            ?.toMutableMap()
            ?: mutableMapOf()
        val isResuming = validCheckpoint != null

        fun checkpointSnapshot(): StorageScanCheckpoint = StorageScanCheckpoint(
            rootPath = root.toString(),
            files = entriesByPath.values.sortedBy { it.path },
            directories = directories.sorted(),
            sampleHashes = sampleHashes.toMap(),
            fullHashes = fullHashes.toMap(),
            hashBytesRead = hashBytesRead,
            hashCandidates = hashCandidates,
            skippedFiles = skippedFiles,
        )

        fun persistCheckpoint() {
            runCatching { onCheckpoint(checkpointSnapshot()) }
        }

        fun emitProgress(phase: StorageScanPhase) {
            onProgress(
                StorageScanProgress(
                    files = fileCount,
                    directories = directoryCount,
                    phase = phase,
                    skippedFiles = skippedFiles,
                    hashBytesRead = hashBytesRead,
                    isResuming = isResuming,
                ),
            )
        }

        emitProgress(StorageScanPhase.INDEXING)
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                scanContext.ensureActive()
                val normalized = normalizePath(dir)
                if (normalized != root.toString() && normalized !in directories) {
                    if (directoryCount >= limits.maxDirectories) {
                        bounded = true
                        skippedFiles++
                        persistCheckpoint()
                        return FileVisitResult.TERMINATE
                    }
                    directoryCount++
                    directories += normalized
                    findOrCreateNode(tree, root, dir, isDirectory = true)
                }
                if (directoryCount % PROGRESS_INTERVAL == 0) emitProgress(StorageScanPhase.INDEXING)
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                scanContext.ensureActive()
                if (Files.isSymbolicLink(file) || !attrs.isRegularFile) {
                    skippedFiles++
                    return FileVisitResult.CONTINUE
                }

                val normalized = normalizePath(file)
                if (normalized in stablePaths) return FileVisitResult.CONTINUE
                if (fileCount >= limits.maxFiles || tree.size >= limits.maxIndexedBytes) {
                    bounded = true
                    skippedFiles++
                    persistCheckpoint()
                    return FileVisitResult.TERMINATE
                }

                val size = attrs.size()
                if (size > limits.maxIndexedBytes - tree.size) {
                    bounded = true
                    skippedFiles++
                    persistCheckpoint()
                    return FileVisitResult.TERMINATE
                }
                val entry = StorageEntry(
                    name = file.fileName?.toString() ?: file.toString(),
                    path = normalized,
                    size = size,
                    modifiedAt = attrs.lastModifiedTime().toMillis(),
                )
                addEntry(entry)
                if (fileCount % PROGRESS_INTERVAL == 0) {
                    emitProgress(StorageScanPhase.INDEXING)
                    persistCheckpoint()
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                skippedFiles++
                inaccessibleEntries++
                return FileVisitResult.CONTINUE
            }
        })

        emitProgress(StorageScanPhase.INDEXING)
        if (bounded) persistCheckpoint()

        val duplicateGroups = mutableListOf<DuplicateGroup>()
        var hashAnalysisComplete = true
        val candidateGroups = candidatesBySize
            .asSequence()
            .filter { (_, entries) -> entries.size > 1 }
            .sortedBy { (size, _) -> size }
            .toList()

        fun budgetAllows(bytes: Long): Boolean =
            hashCandidates < limits.maxHashCandidates &&
                bytes >= 0L &&
                bytes <= limits.maxHashBytes - hashBytesRead

        fun rememberHashProgress() {
            if (hashCandidates > 0 && hashCandidates % HASH_CHECKPOINT_INTERVAL == 0) {
                emitProgress(StorageScanPhase.HASHING)
                persistCheckpoint()
            }
        }

        for ((size, entries) in candidateGroups) {
            scanContext.ensureActive()
            val sampleGroups = linkedMapOf<String, MutableList<StorageEntry>>()
            for (entry in entries.sortedBy { it.path }) {
                val sample = sampleHashes[entry.path] ?: run {
                    val expectedBytes = sampleBytesFor(entry.size, limits.sampleBytes)
                    if (!budgetAllows(expectedBytes)) {
                        hashAnalysisComplete = false
                        continue
                    }
                    val sampled = try {
                        sampleSha256(entry.path, entry.size, limits.sampleBytes, scanContext)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: IOException) {
                        null
                    } catch (_: SecurityException) {
                        null
                    }
                    if (sampled == null) {
                        hashAnalysisComplete = false
                        continue
                    }
                    hashBytesRead += sampled.bytesRead
                    hashCandidates++
                    sampleHashes[entry.path] = sampled.value
                    if (entry.size <= limits.sampleBytes) fullHashes[entry.path] = sampled.value
                    rememberHashProgress()
                    sampled.value
                }
                sampleGroups.getOrPut(sample) { mutableListOf() }.add(entry)
            }

            sampleGroups.values
                .filter { it.size > 1 }
                .forEach { possibleDuplicates ->
                    val fullGroups = linkedMapOf<String, MutableList<StorageEntry>>()
                    possibleDuplicates.forEach { entry ->
                        scanContext.ensureActive()
                        val full = fullHashes[entry.path] ?: run {
                            if (!budgetAllows(entry.size)) {
                                hashAnalysisComplete = false
                                return@forEach
                            }
                            val hashed = try {
                                sha256(entry.path, scanContext)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: IOException) {
                                null
                            } catch (_: SecurityException) {
                                null
                            }
                            if (hashed == null) {
                                hashAnalysisComplete = false
                                return@forEach
                            }
                            hashBytesRead += hashed.bytesRead
                            fullHashes[entry.path] = hashed.value
                            hashed.value
                        }
                        fullGroups.getOrPut(full) { mutableListOf() }.add(entry)
                    }
                    fullGroups.values
                        .filter { it.size > 1 }
                        .forEach { files -> duplicateGroups += DuplicateGroup(size, files.sortedBy { it.path }) }
            }
            emitProgress(StorageScanPhase.HASHING)
            persistCheckpoint()
        }

        val hasMoreWork = bounded || !hashAnalysisComplete
        val checkpoint = if (hasMoreWork) checkpointSnapshot() else null
        emitProgress(StorageScanPhase.HASHING)
        StorageScanResult(
            root = tree.toModel(),
            totalBytes = tree.size,
            fileCount = fileCount,
            directoryCount = directoryCount,
            largestFiles = largest.toList().sortedWith(compareByDescending<StorageEntry> { it.size }.thenBy { it.path }),
            duplicateGroups = duplicateGroups
                .distinctBy { group -> group.files.joinToString("|") { it.path } }
                .sortedWith(compareByDescending<DuplicateGroup> { it.size }.thenBy { it.files.first().path })
                .take(MAX_DUPLICATE_GROUPS),
            isComplete = !bounded,
            hashAnalysisComplete = hashAnalysisComplete,
            skippedFiles = skippedFiles,
            inaccessibleEntries = inaccessibleEntries,
            hashBytesRead = hashBytesRead,
            hashCandidates = hashCandidates,
            checkpoint = checkpoint,
        )
    }

    private fun stableEntry(entry: StorageEntry, root: Path): StorageEntry? = runCatching {
        val path = Paths.get(entry.path).toAbsolutePath().normalize()
        if (path == root || !isWithinRoot(path, root) || entry.modifiedAt <= 0L) return@runCatching null
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!attrs.isRegularFile || attrs.size() != entry.size || attrs.lastModifiedTime().toMillis() != entry.modifiedAt) {
            null
        } else {
            entry.copy(path = path.toString())
        }
    }.getOrNull()

    private fun sampleSha256(
        path: String,
        size: Long,
        sampleBytes: Int,
        scanContext: kotlin.coroutines.CoroutineContext,
    ): HashRead {
        val digest = MessageDigest.getInstance("SHA-256")
        var bytesRead = 0L
        if (size > sampleBytes) digest.update("sample:$size:".toByteArray())
        Files.newInputStream(Paths.get(path), StandardOpenOption.READ).use { input ->
            bytesRead += readIntoDigest(input, digest, sampleBytes.toLong(), scanContext)
        }
        if (size > sampleBytes) {
            RandomAccessFile(path, "r").use { file ->
                file.seek((size - sampleBytes).coerceAtLeast(0L))
                bytesRead += readIntoDigest(file, digest, sampleBytes.toLong(), scanContext)
            }
        }
        return HashRead(digest.digest().toHex(), bytesRead)
    }

    private fun sha256(path: String, scanContext: kotlin.coroutines.CoroutineContext): HashRead {
        val digest = MessageDigest.getInstance("SHA-256")
        var bytesRead = 0L
        Files.newInputStream(Paths.get(path), StandardOpenOption.READ).use { input ->
            val buffer = ByteArray(HASH_BUFFER_SIZE)
            while (true) {
                scanContext.ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                bytesRead += read
            }
        }
        return HashRead(digest.digest().toHex(), bytesRead)
    }

    private fun readIntoDigest(
        input: java.io.InputStream,
        digest: MessageDigest,
        maxBytes: Long,
        scanContext: kotlin.coroutines.CoroutineContext,
    ): Long {
        val buffer = ByteArray(HASH_BUFFER_SIZE)
        var remaining = maxBytes
        var bytesRead = 0L
        while (remaining > 0) {
            scanContext.ensureActive()
            val read = input.read(buffer, 0, min(buffer.size.toLong(), remaining).toInt())
            if (read < 0) break
            digest.update(buffer, 0, read)
            bytesRead += read
            remaining -= read
        }
        return bytesRead
    }

    private fun readIntoDigest(
        input: RandomAccessFile,
        digest: MessageDigest,
        maxBytes: Long,
        scanContext: kotlin.coroutines.CoroutineContext,
    ): Long {
        val buffer = ByteArray(HASH_BUFFER_SIZE)
        var remaining = maxBytes
        var bytesRead = 0L
        while (remaining > 0) {
            scanContext.ensureActive()
            val read = input.read(buffer, 0, min(buffer.size.toLong(), remaining).toInt())
            if (read < 0) break
            digest.update(buffer, 0, read)
            bytesRead += read
            remaining -= read
        }
        return bytesRead
    }

    private fun addToTree(root: MutableNode, rootPath: Path, file: Path, size: Long) {
        root.add(size, 1)
        var current = root
        for (part in rootPath.relativize(file)) {
            val childPath = current.pathAsPath().resolve(part).toString()
            val child = current.children.getOrPut(part.toString()) {
                MutableNode(part.toString(), childPath)
            }
            child.add(size, 1)
            current = child
        }
        current.isDirectory = false
    }

    private fun findOrCreateNode(root: MutableNode, rootPath: Path, directory: Path, isDirectory: Boolean): MutableNode {
        var current = root
        for (part in rootPath.relativize(directory)) {
            val childPath = current.pathAsPath().resolve(part).toString()
            current = current.children.getOrPut(part.toString()) {
                MutableNode(part.toString(), childPath, isDirectory = isDirectory)
            }
        }
        current.isDirectory = isDirectory
        return current
    }

    private class MutableNode(
        val name: String,
        val path: String,
        var isDirectory: Boolean = true,
    ) {
        var size: Long = 0L
        var fileCount: Int = 0
        val children: MutableMap<String, MutableNode> = linkedMapOf()

        fun add(bytes: Long, files: Int) {
            size = if (Long.MAX_VALUE - size < bytes) Long.MAX_VALUE else size + bytes
            fileCount = (fileCount + files).coerceAtMost(Int.MAX_VALUE)
        }

        fun pathAsPath(): Path = Paths.get(path)

        fun toModel(): StorageTreeNode {
            val sorted = children.values.sortedWith(compareByDescending<MutableNode> { it.size }.thenBy { it.name })
            val visible = sorted.take(MAX_TREE_CHILDREN).map { it.toModel() }.toMutableList()
            val hidden = sorted.drop(MAX_TREE_CHILDREN)
            if (hidden.isNotEmpty()) {
                visible += StorageTreeNode(
                    name = "Other",
                    path = "$path/*",
                    size = hidden.sumOf { it.size },
                    fileCount = hidden.sumOf { it.fileCount },
                    isDirectory = true,
                    isAggregate = true,
                )
            }
            return StorageTreeNode(name, path, size, fileCount, isDirectory, children = visible)
        }
    }

    private data class HashRead(val value: String, val bytesRead: Long)

    private companion object {
        const val HASH_BUFFER_SIZE = 64 * 1024
        const val HASH_CHECKPOINT_INTERVAL = 25
        const val MAX_DUPLICATE_GROUPS = 100
        const val MAX_LARGEST_FILES = 100
        const val MAX_TREE_CHILDREN = 24
        const val PROGRESS_INTERVAL = 250

        fun normalizePath(path: String): String = Paths.get(path).toAbsolutePath().normalize().toString()

        fun normalizePath(path: Path): String = path.toAbsolutePath().normalize().toString()

        fun isWithinRoot(candidate: Path, root: Path): Boolean {
            val normalizedCandidate = candidate.toAbsolutePath().normalize()
            val normalizedRoot = root.toAbsolutePath().normalize()
            return normalizedCandidate == normalizedRoot || normalizedCandidate.startsWith(normalizedRoot)
        }

        fun sampleBytesFor(size: Long, sampleBytes: Int): Long =
            if (size <= sampleBytes) size else sampleBytes.toLong() * 2L

        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
