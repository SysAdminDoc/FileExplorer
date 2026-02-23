package com.explorer.fileexplorer.core.data

import com.explorer.fileexplorer.core.storage.RootHelper
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routes filesystem operations to the correct repository implementation
 * based on path type and available capabilities.
 * Analogous to PowerShell's PSDrive/Provider routing.
 */
@Singleton
class FileRepositoryFactory @Inject constructor(
    private val localRepo: LocalFileRepository,
    private val rootRepo: RootFileRepository,
    private val rootHelper: RootHelper,
) {
    /**
     * Get the appropriate repository for a given path.
     * Priority: root (if enabled + needed) > local
     * Future: archive://, smb://, sftp://, gdrive:// schemes
     */
    fun getRepository(path: String): FileRepository {
        // Future: check URI scheme for network/cloud/archive providers
        // if (path.startsWith("smb://")) return smbRepo
        // if (path.startsWith("sftp://")) return sftpRepo

        // Use root repo if root is enabled and path requires it
        if (rootHelper.rootEnabled.value && rootHelper.isRooted) {
            if (rootHelper.requiresRoot(path)) return rootRepo
            // Also use root for paths that aren't readable normally
            if (!isNormallyReadable(path)) return rootRepo
        }

        return localRepo
    }

    /** Check if the default local repo can handle this path. */
    private fun isNormallyReadable(path: String): Boolean {
        return try {
            java.io.File(path).canRead()
        } catch (_: Exception) {
            false
        }
    }
}
