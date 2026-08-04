package com.explorer.fileexplorer.core.data

import com.explorer.fileexplorer.core.storage.RootHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

enum class RootModuleManager(val displayName: String) {
    MAGISK("Magisk"),
    KERNELSU("KernelSU"),
    APATCH("APatch"),
    UNKNOWN("Root manager"),
}

data class RootModule(
    val id: String,
    val name: String,
    val version: String,
    val versionCode: Int?,
    val author: String,
    val description: String,
    val path: String,
    val manager: RootModuleManager,
    val enabled: Boolean,
    val pendingRemoval: Boolean,
    val skipMount: Boolean,
)

data class RootModuleSnapshot(
    val manager: RootModuleManager,
    val modules: List<RootModule>,
)

object RootModuleParser {
    private val moduleIdPattern = Regex("^[A-Za-z][A-Za-z0-9._-]+$")

    fun isValidModuleId(id: String): Boolean = moduleIdPattern.matches(id)

    fun parseProperties(content: String): Map<String, String> = content
        .lineSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            val key = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()
            key.takeIf { it.isNotEmpty() }?.let { it to value }
        }
        .toMap()

    fun parse(
        modulePath: String,
        manager: RootModuleManager,
        moduleProp: String,
        disabled: Boolean,
        pendingRemoval: Boolean,
        skipMount: Boolean,
    ): RootModule? {
        val properties = parseProperties(moduleProp)
        val id = properties["id"] ?: modulePath.substringAfterLast('/')
        if (!isValidModuleId(id) || modulePath != "$MODULES_ROOT/$id") return null

        return RootModule(
            id = id,
            name = properties["name"].orEmpty().ifBlank { id },
            version = properties["version"].orEmpty().ifBlank { "Unknown version" },
            versionCode = properties["versionCode"]?.toIntOrNull(),
            author = properties["author"].orEmpty().ifBlank { "Unknown author" },
            description = properties["description"].orEmpty(),
            path = modulePath,
            manager = manager,
            enabled = !disabled,
            pendingRemoval = pendingRemoval,
            skipMount = skipMount,
        )
    }

    private const val MODULES_ROOT = "/data/adb/modules"
}

@Singleton
class RootModuleRepository @Inject constructor(
    private val rootHelper: RootHelper,
) {
    suspend fun listModules(): Result<RootModuleSnapshot> = withContext(Dispatchers.IO) {
        if (!rootHelper.isRooted || !rootHelper.rootEnabled.value) {
            return@withContext Result.failure(
                IllegalStateException("Enable Root Mode before managing root modules"),
            )
        }

        val manager = detectManager()
        val modulesRoot = ROOT_MODULES
        if (!rootHelper.exec("test -d ${quote(modulesRoot)}").isSuccess) {
            return@withContext Result.success(RootModuleSnapshot(manager, emptyList()))
        }

        val moduleProps = rootHelper.exec(
            "find ${quote(modulesRoot)} -mindepth 2 -maxdepth 2 -type f -name module.prop " +
                "-print 2>/dev/null | sort | head -256",
        )
        if (!moduleProps.isSuccess) {
            return@withContext Result.failure(
                IllegalStateException(moduleProps.err.joinToString().ifBlank { "Unable to list root modules" }),
            )
        }

        val modules = moduleProps.out.mapNotNull { propPath ->
            val modulePath = modulePathFor(propPath.trim()) ?: return@mapNotNull null
            val propResult = rootHelper.exec("cat ${quote(propPath.trim())}")
            if (!propResult.isSuccess) return@mapNotNull null

            val disabled = rootHelper.exec(
                "test -e ${quote("$modulePath/disable")} || test -e ${quote("$modulePath/.disable")}",
            ).isSuccess
            val pendingRemoval = rootHelper.exec("test -e ${quote("$modulePath/remove")}").isSuccess
            val skipMount = rootHelper.exec("test -e ${quote("$modulePath/skip_mount")}").isSuccess
            RootModuleParser.parse(
                modulePath = modulePath,
                manager = manager,
                moduleProp = propResult.out.joinToString("\n"),
                disabled = disabled,
                pendingRemoval = pendingRemoval,
                skipMount = skipMount,
            )
        }.sortedBy { it.name.lowercase() }

        Result.success(RootModuleSnapshot(manager, modules))
    }

    suspend fun setEnabled(module: RootModule, enabled: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        if (!rootHelper.isRooted || !rootHelper.rootEnabled.value) {
            return@withContext Result.failure(
                IllegalStateException("Enable Root Mode before changing module state"),
            )
        }
        val modulePath = modulePathFor(module.path) ?: return@withContext Result.failure(
            IllegalArgumentException("Invalid module path"),
        )
        val result = if (enabled) {
            rootHelper.exec("rm -f ${quote("$modulePath/disable")} ${quote("$modulePath/.disable")}")
        } else {
            rootHelper.exec("touch ${quote("$modulePath/disable")}")
        }
        if (result.isSuccess) Result.success(Unit)
        else Result.failure(IllegalStateException(result.err.joinToString().ifBlank { "Unable to change module state" }))
    }

    suspend fun installModule(zipPath: String): Result<String> = withContext(Dispatchers.IO) {
        if (!rootHelper.isRooted || !rootHelper.rootEnabled.value) {
            return@withContext Result.failure(
                IllegalStateException("Enable Root Mode before installing a module"),
            )
        }
        if (!rootHelper.exec("test -f ${quote(zipPath)}").isSuccess) {
            return@withContext Result.failure(IllegalArgumentException("Selected ZIP is unavailable"))
        }

        val manager = detectManager()
        val command = when (manager) {
            RootModuleManager.APATCH -> "apd module install ${quote(zipPath)}"
            RootModuleManager.KERNELSU -> "ksud module install ${quote(zipPath)}"
            RootModuleManager.MAGISK -> "magisk --install-module ${quote(zipPath)}"
            RootModuleManager.UNKNOWN -> return@withContext Result.failure(
                IllegalStateException("No supported Magisk, KernelSU, or APatch installer was found"),
            )
        }
        val result = rootHelper.exec(command)
        if (!result.isSuccess) {
            Result.failure(IllegalStateException(result.err.joinToString().ifBlank { "Module installation failed" }))
        } else {
            val output = (result.out + result.err).lastOrNull { it.isNotBlank() }
                ?: "Module installer completed; reboot may be required"
            Result.success(output.trim())
        }
    }

    private suspend fun detectManager(): RootModuleManager {
        if (rootHelper.exec("command -v apd >/dev/null 2>&1 || test -x /data/adb/apd").isSuccess) {
            return RootModuleManager.APATCH
        }
        if (rootHelper.exec("command -v ksud >/dev/null 2>&1 || test -x /data/adb/ksu/bin/ksud").isSuccess) {
            return RootModuleManager.KERNELSU
        }
        if (rootHelper.exec("command -v magisk >/dev/null 2>&1 || test -x /data/adb/magisk/magisk").isSuccess) {
            return RootModuleManager.MAGISK
        }
        return RootModuleManager.UNKNOWN
    }

    private fun modulePathFor(path: String): String? {
        val prefix = "$ROOT_MODULES/"
        if (!path.startsWith(prefix)) return null
        val id = path.removePrefix(prefix).substringBefore('/')
        if (!RootModuleParser.isValidModuleId(id)) return null
        return "$prefix$id"
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private companion object {
        const val ROOT_MODULES = "/data/adb/modules"
    }
}
