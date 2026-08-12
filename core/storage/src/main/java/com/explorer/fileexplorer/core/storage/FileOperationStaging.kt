package com.explorer.fileexplorer.core.storage

import java.io.File
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Base64

enum class StagingRecoveryPolicy {
    CLEAN,
    ROLLBACK,
}

data class StagedEntry(
    val originalPath: String,
    val stagedPath: String,
    val committedPath: String? = null,
    val state: String = FileOperationStaging.STATE_PLANNED,
)

data class StagingManifest(
    val operation: String,
    val recoveryPolicy: StagingRecoveryPolicy,
    val entries: List<StagedEntry> = emptyList(),
)

/**
 * Durable, filesystem-local staging for operations that touch more than one path.
 * The manifest is replaced atomically before and after every visible mutation.
 */
object FileOperationStaging {
    const val ROOT_DIRECTORY = ".fileexplorer-staging"
    const val STATE_PLANNED = "PLANNED"
    const val STATE_STAGED = "STAGED"
    const val STATE_BACKUP_MOVED = "BACKUP_MOVED"
    const val STATE_TARGET_COMMITTED = "TARGET_COMMITTED"
    const val STATE_COMMITTED = "COMMITTED"

    private const val MANIFEST_NAME = "manifest"
    private const val TEMP_MANIFEST_NAME = ".manifest.tmp"
    private const val FORMAT = "FILEEXPLORER-STAGING-1"

    fun createOperation(baseDirectory: File, operation: String, recoveryPolicy: StagingRecoveryPolicy): File {
        require(operation.isNotBlank()) { "Staging operation must not be blank" }
        if (!baseDirectory.exists() && !baseDirectory.mkdirs() && !baseDirectory.isDirectory) {
            throw IllegalStateException("Unable to create staging base: ${baseDirectory.path}")
        }
        val root = baseDirectory.resolve(ROOT_DIRECTORY)
        if (!root.exists() && !root.mkdirs() && !root.isDirectory) {
            throw IllegalStateException("Unable to create staging directory: ${root.path}")
        }
        val operationRoot = Files.createTempDirectory(root.toPath(), ".$operation-").toFile()
        writeManifest(operationRoot, StagingManifest(operation, recoveryPolicy))
        return operationRoot
    }

    fun writeManifest(operationRoot: File, manifest: StagingManifest) {
        if (!operationRoot.exists() && !operationRoot.mkdirs() && !operationRoot.isDirectory) {
            throw IllegalStateException("Unable to create operation journal: ${operationRoot.path}")
        }
        val temporary = operationRoot.resolve(TEMP_MANIFEST_NAME)
        temporary.outputStream().bufferedWriter(StandardCharsets.UTF_8).use { writer ->
            writer.appendLine(FORMAT)
            writer.appendLine(
                listOf(
                    encode(manifest.operation),
                    manifest.recoveryPolicy.name,
                ).joinToString("|"),
            )
            manifest.entries.forEach { entry ->
                writer.appendLine(
                    listOf(
                        encode(entry.originalPath),
                        encode(entry.stagedPath),
                        encode(entry.committedPath ?: ""),
                        entry.state,
                    ).joinToString("|"),
                )
            }
        }
        forceFile(temporary)
        atomicMove(temporary.toPath(), operationRoot.resolve(MANIFEST_NAME).toPath(), replaceExisting = true)
        forceDirectory(operationRoot)
    }

    fun readManifest(operationRoot: File): StagingManifest? {
        val manifestFile = operationRoot.resolve(MANIFEST_NAME)
        if (!manifestFile.isFile) return null
        return runCatching {
            val lines = manifestFile.readLines(StandardCharsets.UTF_8)
            require(lines.firstOrNull() == FORMAT) { "Unsupported staging manifest" }
            val header = lines.getOrNull(1)?.split('|') ?: error("Missing staging manifest header")
            require(header.size == 2) { "Invalid staging manifest header" }
            val policy = StagingRecoveryPolicy.valueOf(header[1])
            val entries = lines.drop(2).filter { it.isNotBlank() }.map { line ->
                val fields = line.split('|')
                require(fields.size == 4) { "Invalid staging manifest entry" }
                StagedEntry(
                    originalPath = decode(fields[0]),
                    stagedPath = decode(fields[1]),
                    committedPath = decode(fields[2]).ifBlank { null },
                    state = fields[3].also { require(it.isNotBlank()) },
                )
            }
            StagingManifest(decode(header[0]), policy, entries)
        }.getOrNull()
    }

    /**
     * Reconciles abandoned journals from a previous process. ROLLBACK journals restore
     * entries that were moved out of their original location; CLEAN journals discard
     * private staging data. Malformed journals are removed rather than exposed as files.
     */
    fun recoverOrphaned(baseDirectory: File): Int {
        val root = baseDirectory.resolve(ROOT_DIRECTORY)
        val operations = root.listFiles().orEmpty().filter { it.isDirectory }
        var recovered = 0
        operations.forEach { operationRoot ->
            val manifest = readManifest(operationRoot)
            if (manifest == null) {
                deleteRecursively(operationRoot)
                return@forEach
            }
            when (manifest.recoveryPolicy) {
                StagingRecoveryPolicy.CLEAN -> Unit
                StagingRecoveryPolicy.ROLLBACK -> recoverRollback(manifest).also { recovered += it }
            }
            deleteRecursively(operationRoot)
        }
        return recovered
    }

    private fun recoverRollback(manifest: StagingManifest): Int {
        var recovered = 0
        val restoredBackups = hashSetOf<String>()
        manifest.entries
            .filter { it.state == STATE_BACKUP_MOVED }
            .forEach { entry ->
                val original = File(entry.originalPath)
                val staged = File(entry.stagedPath)
                if (staged.exists()) {
                    deleteIfExists(original)
                    original.parentFile?.mkdirs()
                    runCatching { atomicMove(staged.toPath(), original.toPath(), replaceExisting = false) }
                        .onSuccess {
                            recovered++
                            restoredBackups += original.path
                        }
                }
            }

        manifest.entries.forEach { entry ->
            if (entry.state == STATE_COMMITTED) {
                deleteIfExists(File(entry.stagedPath))
                return@forEach
            }
            val original = File(entry.originalPath)
            val staged = File(entry.stagedPath)
            when (entry.state) {
                STATE_TARGET_COMMITTED -> {
                    val committed = entry.committedPath?.let(::File)
                    if (manifest.operation == "move" && !original.exists() && committed?.exists() == true) {
                        original.parentFile?.mkdirs()
                        runCatching { atomicMove(committed.toPath(), original.toPath(), replaceExisting = false) }
                            .onSuccess { recovered++ }
                    } else {
                        if (committed?.path !in restoredBackups) committed?.let(::deleteIfExists)
                    }
                    if (staged.exists()) deleteRecursively(staged)
                }
                STATE_STAGED -> {
                    if (manifest.operation in setOf("delete", "move") && !original.exists() && staged.exists()) {
                        original.parentFile?.mkdirs()
                        runCatching { atomicMove(staged.toPath(), original.toPath(), replaceExisting = false) }
                            .onSuccess { recovered++ }
                    } else if (staged.exists()) {
                        deleteRecursively(staged)
                    }
                }
                else -> {
                    if (staged.exists()) deleteRecursively(staged)
                }
            }
        }
        return recovered
    }

    fun deleteIfExists(file: File) {
        if (file.exists() || Files.isSymbolicLink(file.toPath())) deleteRecursively(file)
    }

    fun deleteRecursively(file: File) {
        if (Files.isSymbolicLink(file.toPath())) {
            Files.deleteIfExists(file.toPath())
            return
        }
        file.listFiles().orEmpty().forEach(::deleteRecursively)
        Files.deleteIfExists(file.toPath())
    }

    fun atomicMove(source: Path, target: Path, replaceExisting: Boolean) {
        val options = if (replaceExisting) {
            arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } else {
            arrayOf(StandardCopyOption.ATOMIC_MOVE)
        }
        try {
            Files.move(source, target, *options)
        } catch (_: AtomicMoveNotSupportedException) {
            val fallback = if (replaceExisting) {
                arrayOf(StandardCopyOption.REPLACE_EXISTING)
            } else {
                emptyArray()
            }
            Files.move(source, target, *fallback)
        }
    }

    fun forceFile(file: File) {
        if (!file.isFile) return
        runCatching {
            FileChannel.open(file.toPath(), StandardOpenOption.WRITE).use { channel -> channel.force(true) }
        }
    }

    fun forceDirectory(directory: File) {
        runCatching {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { channel -> channel.force(true) }
        }
    }

    private fun encode(value: String): String = Base64.getEncoder()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decode(value: String): String = String(
        Base64.getDecoder().decode(value),
        StandardCharsets.UTF_8,
    )
}
