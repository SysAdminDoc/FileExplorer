package com.explorer.fileexplorer.core.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.PriorityQueue
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class StorageEntry(
    val name: String,
    val path: String,
    val size: Long,
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

data class StorageScanProgress(
    val files: Int = 0,
    val directories: Int = 0,
)

data class StorageScanResult(
    val root: StorageTreeNode,
    val totalBytes: Long,
    val fileCount: Int,
    val directoryCount: Int,
    val largestFiles: List<StorageEntry>,
    val duplicateGroups: List<DuplicateGroup>,
)

@Singleton
class StorageAnalyzer @Inject constructor() {

    suspend fun scan(
        rootPath: String,
        onProgress: (StorageScanProgress) -> Unit = {},
    ): StorageScanResult = withContext(Dispatchers.IO) {
        val scanContext = currentCoroutineContext()
        val root = Paths.get(rootPath).toAbsolutePath().normalize()
        require(Files.isDirectory(root)) { "Not a directory: $rootPath" }

        val tree = MutableNode(name = root.fileName?.toString() ?: root.toString(), path = root.toString())
        val candidatesBySize = mutableMapOf<Long, MutableList<StorageEntry>>()
        val largest = PriorityQueue<StorageEntry>(compareBy { it.size })
        var fileCount = 0
        var directoryCount = 0

        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                scanContext.ensureActive()
                if (dir != root) {
                    directoryCount++
                    findOrCreateNode(tree, root, dir, isDirectory = true)
                }
                if (directoryCount % PROGRESS_INTERVAL == 0) {
                    onProgress(StorageScanProgress(fileCount, directoryCount))
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                scanContext.ensureActive()
                if (!attrs.isRegularFile) return FileVisitResult.CONTINUE
                val entry = StorageEntry(
                    name = file.fileName?.toString() ?: file.toString(),
                    path = file.toString(),
                    size = attrs.size(),
                )
                fileCount++
                addToTree(tree, root, file, entry.size)
                candidatesBySize.getOrPut(entry.size) { mutableListOf() }.add(entry)
                largest.add(entry)
                if (largest.size > MAX_LARGEST_FILES) largest.poll()
                if (fileCount % PROGRESS_INTERVAL == 0) {
                    onProgress(StorageScanProgress(fileCount, directoryCount))
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult {
                return FileVisitResult.CONTINUE
            }
        })

        onProgress(StorageScanProgress(fileCount, directoryCount))
        val duplicateGroups = candidatesBySize
            .asSequence()
            .filter { (_, entries) -> entries.size > 1 }
            .flatMap { (size, entries) ->
                entries.mapNotNull { entry ->
                    val hash = try {
                        sha256(entry.path, scanContext)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: java.io.IOException) {
                        null
                    } catch (_: SecurityException) {
                        null
                    }
                    hash?.let { it to entry }
                }.groupBy({ it.first }, { it.second })
                    .values
                    .asSequence()
                    .filter { it.size > 1 }
                    .map { files -> DuplicateGroup(size, files.sortedBy { it.path }) }
            }
            .sortedWith(compareByDescending<DuplicateGroup> { it.size }.thenBy { it.files.first().path })
            .take(MAX_DUPLICATE_GROUPS)
            .toList()

        StorageScanResult(
            root = tree.toModel(),
            totalBytes = tree.size,
            fileCount = fileCount,
            directoryCount = directoryCount,
            largestFiles = largest.toList().sortedByDescending { it.size },
            duplicateGroups = duplicateGroups,
        )
    }

    private fun sha256(path: String, scanContext: kotlin.coroutines.CoroutineContext): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(Paths.get(path)).use { input ->
            val buffer = ByteArray(HASH_BUFFER_SIZE)
            while (true) {
                scanContext.ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
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
            size += bytes
            fileCount += files
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

    private companion object {
        const val HASH_BUFFER_SIZE = 64 * 1024
        const val MAX_DUPLICATE_GROUPS = 100
        const val MAX_LARGEST_FILES = 100
        const val MAX_TREE_CHILDREN = 24
        const val PROGRESS_INTERVAL = 250
    }
}
