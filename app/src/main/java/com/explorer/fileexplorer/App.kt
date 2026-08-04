package com.explorer.fileexplorer

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.explorer.fileexplorer.core.data.FileRepositoryFactory
import com.explorer.fileexplorer.core.data.NetworkRepoProvider
import com.explorer.fileexplorer.core.data.PluginFileRepository
import com.explorer.fileexplorer.core.data.SchemeResolver
import com.explorer.fileexplorer.core.model.ConflictResolution
import com.explorer.fileexplorer.core.model.FileItem
import com.explorer.fileexplorer.core.network.NetworkFileRepository
import com.explorer.fileexplorer.core.network.smb.SmbFileRepository
import com.explorer.fileexplorer.core.network.sftp.SftpFileRepository
import com.explorer.fileexplorer.core.network.ftp.FtpFileRepository
import com.explorer.fileexplorer.core.network.webdav.WebDavFileRepository
import com.explorer.fileexplorer.plugin.PluginManager
import com.explorer.fileexplorer.feature.settings.ThumbnailCacheController
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

@HiltAndroidApp
class App : Application(), SingletonImageLoader.Factory {

    @Inject lateinit var repoFactory: FileRepositoryFactory
    @Inject lateinit var smbRepo: SmbFileRepository
    @Inject lateinit var sftpRepo: SftpFileRepository
    @Inject lateinit var ftpRepo: FtpFileRepository
    @Inject lateinit var webDavRepo: WebDavFileRepository
    @Inject lateinit var pluginManager: PluginManager

    override fun onCreate() {
        super.onCreate()
        SingletonImageLoader.setSafe(this)
        repoFactory.registerSchemeResolver { scheme ->
            val repo: NetworkFileRepository? = when (scheme) {
                "smb" -> smbRepo.takeIf { it.isConnected }
                "sftp" -> sftpRepo.takeIf { it.isConnected }
                "ftp", "ftps" -> ftpRepo.takeIf { it.isConnected }
                "webdav", "webdavs" -> webDavRepo.takeIf { it.isConnected }
                else -> null
            }
            repo?.let { wrap(it) }
        }
        repoFactory.registerPluginResolver { scheme ->
            pluginManager.findByScheme(scheme)?.let { PluginFileRepository(pluginManager, it) }
        }
    }

    override fun newImageLoader(context: Context): ImageLoader = ThumbnailCacheController.newImageLoader(context)

    private fun wrap(repo: NetworkFileRepository) = object : NetworkRepoProvider {
        override fun listFiles(path: String): Flow<List<FileItem>> = repo.listFiles(path)
        override suspend fun getFileInfo(path: String): FileItem? = repo.getFileInfo(path)
        override suspend fun exists(path: String): Boolean = repo.exists(path)
        override suspend fun copyFiles(sources: List<String>, destination: String, conflictResolution: ConflictResolution, onProgress: (Long, Long, String) -> Unit): Result<Int> =
            repo.copyFiles(sources, destination, conflictResolution, onProgress)
        override suspend fun moveFiles(sources: List<String>, destination: String, conflictResolution: ConflictResolution, onProgress: (Long, Long, String) -> Unit): Result<Int> =
            repo.moveFiles(sources, destination, conflictResolution, onProgress)
        override suspend fun deleteFiles(paths: List<String>, onProgress: (String) -> Unit): Result<Int> =
            repo.deleteFiles(paths, onProgress)
        override suspend fun createDirectory(path: String): Result<FileItem> = repo.createDirectory(path)
        override suspend fun rename(path: String, newName: String): Result<FileItem> = repo.rename(path, newName)
    }
}
