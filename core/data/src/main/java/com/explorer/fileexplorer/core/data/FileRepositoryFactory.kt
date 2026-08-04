package com.explorer.fileexplorer.core.data

import com.explorer.fileexplorer.core.storage.RootHelper
import com.explorer.fileexplorer.core.storage.ShizukuManager
import com.explorer.fileexplorer.core.storage.ShizukuPaths
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileRepositoryFactory @Inject constructor(
    private val localRepo: LocalFileRepository,
    private val rootRepo: RootFileRepository,
    private val shizukuRepo: ShizukuFileRepository,
    private val rootHelper: RootHelper,
    private val shizukuManager: ShizukuManager,
) {
    @Volatile
    private var schemeResolver: SchemeResolver? = null

    fun registerSchemeResolver(resolver: SchemeResolver) {
        schemeResolver = resolver
    }

    fun getRepository(path: String): FileRepository {
        if (ShizukuPaths.isAllowed(path) && shizukuManager.status.value.isReady) return shizukuRepo
        val schemeEnd = path.indexOf("://")
        if (schemeEnd > 0) {
            val scheme = path.substring(0, schemeEnd).lowercase()
            schemeResolver?.resolve(scheme)?.let { return NetworkRepoAdapter(it) }
        }

        if (rootHelper.rootEnabled.value && rootHelper.isRooted) {
            if (rootHelper.requiresRoot(path)) return rootRepo
            if (!isNormallyReadable(path)) return rootRepo
        }

        return if (ShizukuPaths.isAllowed(path)) shizukuRepo else localRepo
    }

    private fun isNormallyReadable(path: String): Boolean {
        return try {
            java.io.File(path).canRead()
        } catch (_: Exception) {
            false
        }
    }
}
