package com.explorer.fileexplorer.core.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Properties
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class TrashItem(
    val id: String,
    val name: String,
    val originalPath: String,
    val trashedPath: String,
    val deletedAt: Long,
    val size: Long,
    val isDirectory: Boolean,
)

@Singleton
class LocalTrashManager @Inject constructor() {

    suspend fun moveToTrash(
        paths: List<String>,
        volumeRoots: List<String> = emptyList(),
        onProgress: (String) -> Unit = {},
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val roots = normalizeRoots(volumeRoots)
            var count = 0
            for (path in paths) {
                val source = Paths.get(path).toAbsolutePath().normalize()
                if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) throw NoSuchFileException(path)
                if (isInsideTrash(source)) throw IOException("Items already in Trash must be restored or permanently deleted")

                val trashRoot = trashRootFor(source, roots)
                val filesDir = trashRoot.resolve(FILES_DIR)
                val infoDir = trashRoot.resolve(INFO_DIR)
                Files.createDirectories(filesDir)
                Files.createDirectories(infoDir)
                Files.write(
                    trashRoot.resolve(".nomedia"),
                    ByteArray(0),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                )

                val id = "${System.currentTimeMillis()}-${UUID.randomUUID()}"
                val itemDir = filesDir.resolve(id)
                Files.createDirectories(itemDir)
                val target = itemDir.resolve(source.fileName.toString())
                onProgress(source.fileName.toString())

                movePath(source, target)
                writeInfo(
                    infoDir.resolve("$id$INFO_EXTENSION"),
                    id = id,
                    source = source,
                    target = target,
                    deletedAt = System.currentTimeMillis(),
                )
                count++
            }
            Result.success(count)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun listTrashItems(volumeRoots: List<String>): Result<List<TrashItem>> = withContext(Dispatchers.IO) {
        try {
            val items = trashRootsFor(volumeRoots).flatMap { trashRoot ->
                val infoDir = trashRoot.resolve(INFO_DIR)
                if (!Files.isDirectory(infoDir, LinkOption.NOFOLLOW_LINKS)) {
                    emptyList()
                } else {
                    Files.newDirectoryStream(infoDir, "*$INFO_EXTENSION").use { stream ->
                        stream.mapNotNull { infoPath ->
                            try {
                                val item = readInfo(infoPath)
                                if (Files.exists(Paths.get(item.trashedPath), LinkOption.NOFOLLOW_LINKS)) {
                                    item
                                } else {
                                    Files.deleteIfExists(infoPath)
                                    null
                                }
                            } catch (_: Exception) {
                                null
                            }
                        }.toList()
                    }
                }
            }
            Result.success(items.sortedByDescending { it.deletedAt })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun restore(ids: List<String>, volumeRoots: List<String>): Result<Int> = withContext(Dispatchers.IO) {
        try {
            var count = 0
            for (infoPath in findInfoFiles(ids, volumeRoots)) {
                val item = readInfo(infoPath)
                val trashed = Paths.get(item.trashedPath).toAbsolutePath().normalize()
                val original = Paths.get(item.originalPath).toAbsolutePath().normalize()
                Files.createDirectories(original.parent)
                val restoreTarget = resolveConflict(original)
                movePath(trashed, restoreTarget)
                cleanupItemContainer(trashed)
                Files.deleteIfExists(infoPath)
                count++
            }
            Result.success(count)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun permanentlyDelete(ids: List<String>, volumeRoots: List<String>): Result<Int> = withContext(Dispatchers.IO) {
        try {
            var count = 0
            for (infoPath in findInfoFiles(ids, volumeRoots)) {
                val item = readInfo(infoPath)
                val trashed = Paths.get(item.trashedPath).toAbsolutePath().normalize()
                deleteRecursive(trashed)
                cleanupItemContainer(trashed)
                Files.deleteIfExists(infoPath)
                count++
            }
            Result.success(count)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun emptyTrash(volumeRoots: List<String>): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val items = listTrashItems(volumeRoots).getOrThrow()
            for (trashRoot in trashRootsFor(volumeRoots)) {
                deleteRecursive(trashRoot)
            }
            Result.success(items.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun purgeExpired(ttlDays: Int, volumeRoots: List<String>): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val cutoff = System.currentTimeMillis() - ttlDays.coerceAtLeast(0) * DAY_MILLIS
            val expiredIds = listTrashItems(volumeRoots).getOrThrow()
                .filter { it.deletedAt <= cutoff }
                .map { it.id }
            permanentlyDelete(expiredIds, volumeRoots)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun trashRootFor(source: Path, volumeRoots: List<Path>): Path {
        val matchingRoot = volumeRoots
            .filter { source.startsWith(it) }
            .maxByOrNull { it.nameCount }

        if (matchingRoot != null) return matchingRoot.resolve(TRASH_DIR_NAME)

        val slashPath = source.toString().replace('\\', '/')
        return when {
            slashPath == "/storage/emulated/0" || slashPath.startsWith("/storage/emulated/0/") ->
                Paths.get("/storage/emulated/0", TRASH_DIR_NAME)
            slashPath == "/sdcard" || slashPath.startsWith("/sdcard/") ->
                Paths.get("/sdcard", TRASH_DIR_NAME)
            slashPath.startsWith("/storage/") -> {
                val parts = slashPath.split('/').filter { it.isNotEmpty() }
                if (parts.size >= 2) Paths.get("/storage", parts[1], TRASH_DIR_NAME)
                else source.parent.resolve(TRASH_DIR_NAME)
            }
            else -> source.parent.resolve(TRASH_DIR_NAME)
        }
    }

    private fun trashRootsFor(volumeRoots: List<String>): List<Path> {
        return normalizeRoots(volumeRoots)
            .map { it.resolve(TRASH_DIR_NAME) }
            .distinctBy { it.toString() }
    }

    private fun normalizeRoots(volumeRoots: List<String>): List<Path> {
        return volumeRoots
            .mapNotNull { root -> runCatching { Paths.get(root).toAbsolutePath().normalize() }.getOrNull() }
            .distinctBy { it.toString() }
    }

    private fun findInfoFiles(ids: List<String>, volumeRoots: List<String>): List<Path> {
        val idSet = ids.toSet()
        return trashRootsFor(volumeRoots).flatMap { trashRoot ->
            val infoDir = trashRoot.resolve(INFO_DIR)
            if (!Files.isDirectory(infoDir, LinkOption.NOFOLLOW_LINKS)) {
                emptyList()
            } else {
                idSet.mapNotNull { id ->
                    val info = infoDir.resolve("$id$INFO_EXTENSION")
                    if (Files.exists(info, LinkOption.NOFOLLOW_LINKS)) info else null
                }
            }
        }
    }

    private fun movePath(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target)
        }
    }

    private fun writeInfo(infoPath: Path, id: String, source: Path, target: Path, deletedAt: Long) {
        val attrs = Files.readAttributes(target, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        val props = Properties().apply {
            setProperty(KEY_ID, id)
            setProperty(KEY_NAME, source.fileName.toString())
            setProperty(KEY_ORIGINAL_PATH, source.toString())
            setProperty(KEY_TRASHED_PATH, target.toString())
            setProperty(KEY_DELETED_AT, deletedAt.toString())
            setProperty(KEY_IS_DIRECTORY, attrs.isDirectory.toString())
        }
        Files.newBufferedWriter(infoPath, StandardCharsets.UTF_8).use { writer ->
            props.store(writer, null)
        }
    }

    private fun readInfo(infoPath: Path): TrashItem {
        val props = Properties()
        Files.newBufferedReader(infoPath, StandardCharsets.UTF_8).use { reader ->
            props.load(reader)
        }
        val trashedPath = requireProperty(props, KEY_TRASHED_PATH)
        return TrashItem(
            id = requireProperty(props, KEY_ID),
            name = props.getProperty(KEY_NAME) ?: Paths.get(trashedPath).fileName.toString(),
            originalPath = requireProperty(props, KEY_ORIGINAL_PATH),
            trashedPath = trashedPath,
            deletedAt = requireProperty(props, KEY_DELETED_AT).toLong(),
            size = calculateSize(Paths.get(trashedPath)),
            isDirectory = props.getProperty(KEY_IS_DIRECTORY)?.toBoolean() ?: Files.isDirectory(
                Paths.get(trashedPath),
                LinkOption.NOFOLLOW_LINKS,
            ),
        )
    }

    private fun requireProperty(props: Properties, key: String): String {
        return props.getProperty(key) ?: throw IOException("Trash metadata missing $key")
    }

    private fun cleanupItemContainer(trashedPath: Path) {
        val itemDir = trashedPath.parent
        if (itemDir?.parent?.fileName?.toString() == FILES_DIR) {
            Files.deleteIfExists(itemDir)
        }
    }

    private fun resolveConflict(target: Path): Path {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return target
        val fileName = target.fileName.toString()
        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        var counter = 1
        var candidate: Path
        do {
            candidate = target.resolveSibling("${base} (restored $counter)$ext")
            counter++
        } while (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS))
        return candidate
    }

    private fun calculateSize(path: Path): Long {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return 0L
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return Files.size(path)

        var total = 0L
        Files.walk(path).use { stream ->
            stream.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .forEach { total += Files.size(it) }
        }
        return total
    }

    private fun deleteRecursive(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.newDirectoryStream(path).use { stream ->
                for (entry in stream) deleteRecursive(entry)
            }
        }
        Files.deleteIfExists(path)
    }

    private fun isInsideTrash(path: Path): Boolean {
        return path.any { it.toString() == TRASH_DIR_NAME }
    }

    companion object {
        const val TRASH_DIR_NAME = ".FileExplorer-Trash"
        const val DEFAULT_TTL_DAYS = 30
        private const val FILES_DIR = "files"
        private const val INFO_DIR = "info"
        private const val INFO_EXTENSION = ".trashinfo"
        private const val KEY_ID = "id"
        private const val KEY_NAME = "name"
        private const val KEY_ORIGINAL_PATH = "originalPath"
        private const val KEY_TRASHED_PATH = "trashedPath"
        private const val KEY_DELETED_AT = "deletedAt"
        private const val KEY_IS_DIRECTORY = "isDirectory"
        private const val DAY_MILLIS = 24L * 60L * 60L * 1000L
    }
}
