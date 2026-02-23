package com.explorer.fileexplorer.core.storage

import com.topjohnwu.superuser.Shell
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RootHelper @Inject constructor() {

    private val _rootState = MutableStateFlow(RootState.UNKNOWN)
    val rootState: StateFlow<RootState> = _rootState.asStateFlow()

    val isRooted: Boolean get() = _rootState.value == RootState.GRANTED
    val isChecked: Boolean get() = _rootState.value != RootState.UNKNOWN

    private var _rootEnabled = MutableStateFlow(false)
    val rootEnabled: StateFlow<Boolean> = _rootEnabled.asStateFlow()

    /** Initialize root shell — call once at app startup. */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        try {
            Shell.enableVerboseLogging = false
            Shell.setDefaultBuilder(
                Shell.Builder.create()
                    .setFlags(Shell.FLAG_MOUNT_MASTER or Shell.FLAG_REDIRECT_STDERR)
                    .setTimeout(10)
            )
            val shell = Shell.getShell()
            _rootState.value = if (shell.isRoot) RootState.GRANTED else RootState.DENIED
        } catch (e: Exception) {
            _rootState.value = RootState.DENIED
        }
    }

    fun setRootEnabled(enabled: Boolean) {
        if (isRooted) _rootEnabled.value = enabled
    }

    /** Execute a root shell command and return stdout lines. */
    suspend fun exec(vararg commands: String): ShellResult = withContext(Dispatchers.IO) {
        if (!isRooted) return@withContext ShellResult(emptyList(), emptyList(), 1)
        val result = Shell.cmd(*commands).exec()
        ShellResult(
            out = result.out,
            err = result.err,
            code = result.code,
        )
    }

    /** Execute and return combined output as single string. */
    suspend fun execString(vararg commands: String): String {
        val result = exec(*commands)
        return result.out.joinToString("\n")
    }

    /** Check if a path requires root to access. */
    fun requiresRoot(path: String): Boolean {
        val restrictedPrefixes = listOf(
            "/data/data", "/data/app", "/data/user",
            "/data/system", "/data/misc",
            "/system", "/vendor", "/product",
            "/efs", "/persist", "/firmware",
        )
        return restrictedPrefixes.any { path.startsWith(it) }
    }
}

data class ShellResult(
    val out: List<String>,
    val err: List<String>,
    val code: Int,
) {
    val isSuccess: Boolean get() = code == 0
}

enum class RootState {
    UNKNOWN, GRANTED, DENIED
}

@Module
@InstallIn(SingletonComponent::class)
object RootModule {
    @Provides
    @Singleton
    fun provideRootHelper(): RootHelper = RootHelper()
}
