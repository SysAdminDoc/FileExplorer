package com.explorer.fileexplorer.feature.network

import android.content.Context
import android.os.Environment
import java.io.File
import java.security.SecureRandom
import java.util.Base64

data class ShareServerConfig(
    val rootPath: String,
    val httpEnabled: Boolean = true,
    val ftpEnabled: Boolean = true,
    val httpPort: Int = DEFAULT_HTTP_PORT,
    val ftpPort: Int = DEFAULT_FTP_PORT,
    val username: String = DEFAULT_USERNAME,
    val password: String,
) {

    fun normalized(): ShareServerConfig = copy(
        rootPath = File(rootPath).canonicalPath,
        httpPort = httpPort.coerceIn(MIN_PORT, MAX_PORT),
        ftpPort = ftpPort.coerceIn(MIN_PORT, MAX_PORT),
        username = username.trim(),
        password = password.trim(),
    )

    companion object {
        const val DEFAULT_HTTP_PORT = 8080
        const val DEFAULT_FTP_PORT = 2121
        const val DEFAULT_USERNAME = "fileexplorer"
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535
    }
}

enum class ShareServerState {
    STOPPED,
    STARTING,
    RUNNING,
    FAILED,
}

data class ShareServerStatus(
    val state: ShareServerState = ShareServerState.STOPPED,
    val config: ShareServerConfig? = null,
    val addresses: List<String> = emptyList(),
    val error: String? = null,
) {
    val isRunning: Boolean
        get() = state == ShareServerState.RUNNING || state == ShareServerState.STARTING
}

class ShareServerSettingsStore(context: Context) {

    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): ShareServerConfig {
        val password = preferences.getString(KEY_PASSWORD, null) ?: generatePassword().also {
            preferences.edit().putString(KEY_PASSWORD, it).apply()
        }
        return ShareServerConfig(
            rootPath = preferences.getString(KEY_ROOT_PATH, defaultRootPath()) ?: defaultRootPath(),
            httpEnabled = preferences.getBoolean(KEY_HTTP_ENABLED, true),
            ftpEnabled = preferences.getBoolean(KEY_FTP_ENABLED, true),
            httpPort = preferences.getInt(KEY_HTTP_PORT, ShareServerConfig.DEFAULT_HTTP_PORT),
            ftpPort = preferences.getInt(KEY_FTP_PORT, ShareServerConfig.DEFAULT_FTP_PORT),
            username = preferences.getString(KEY_USERNAME, ShareServerConfig.DEFAULT_USERNAME)
                ?: ShareServerConfig.DEFAULT_USERNAME,
            password = password,
        )
    }

    fun save(config: ShareServerConfig) {
        preferences.edit()
            .putString(KEY_ROOT_PATH, config.rootPath)
            .putBoolean(KEY_HTTP_ENABLED, config.httpEnabled)
            .putBoolean(KEY_FTP_ENABLED, config.ftpEnabled)
            .putInt(KEY_HTTP_PORT, config.httpPort)
            .putInt(KEY_FTP_PORT, config.ftpPort)
            .putString(KEY_USERNAME, config.username)
            .putString(KEY_PASSWORD, config.password)
            .apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "share_server"
        private const val KEY_ROOT_PATH = "root_path"
        private const val KEY_HTTP_ENABLED = "http_enabled"
        private const val KEY_FTP_ENABLED = "ftp_enabled"
        private const val KEY_HTTP_PORT = "http_port"
        private const val KEY_FTP_PORT = "ftp_port"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"

        @Suppress("DEPRECATION")
        private fun defaultRootPath(): String = Environment.getExternalStorageDirectory().canonicalPath

        private fun generatePassword(): String {
            val bytes = ByteArray(18)
            SecureRandom().nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}
