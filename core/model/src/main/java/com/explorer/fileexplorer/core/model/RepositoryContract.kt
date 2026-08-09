package com.explorer.fileexplorer.core.model

import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.util.concurrent.CancellationException

/** Operations that a file-backed provider can expose. */
enum class RepositoryOperation {
    CONNECT,
    DISCONNECT,
    LIST,
    INFO,
    EXISTS,
    COPY,
    MOVE,
    DELETE,
    CREATE_DIRECTORY,
    CREATE_FILE,
    RENAME,
    SIZE,
    SEARCH,
    CHECKSUM,
    DOWNLOAD,
    UPLOAD,
    AUTHENTICATE,
    REFRESH_TOKEN,
    SIGN_OUT,
    CREATE_FOLDER,
    QUOTA,
}

/** Stable categories used by UI, automation, and diagnostics. */
enum class RepositoryErrorKind {
    UNSUPPORTED,
    AUTHENTICATION,
    PERMISSION,
    TRANSPORT,
    CONFLICT,
    CORRUPT,
    CANCELLED,
    NOT_FOUND,
    INVALID,
    STORAGE,
    UNKNOWN,
}

data class RepositoryError(
    val provider: String,
    val operation: RepositoryOperation,
    val kind: RepositoryErrorKind,
    val message: String,
    val retryable: Boolean,
    val statusCode: Int? = null,
)

/** Exception form of [RepositoryError] for legacy nullable and Flow contracts. */
class RepositoryException(
    val error: RepositoryError,
    cause: Throwable? = null,
) : IOException(error.message, cause)

data class RepositoryCapabilities(
    val provider: String,
    val supportedOperations: Set<RepositoryOperation>,
) {
    fun supports(operation: RepositoryOperation): Boolean = operation in supportedOperations

    companion object {
        val FILE_OPERATIONS: Set<RepositoryOperation> = setOf(
            RepositoryOperation.LIST,
            RepositoryOperation.INFO,
            RepositoryOperation.EXISTS,
            RepositoryOperation.COPY,
            RepositoryOperation.MOVE,
            RepositoryOperation.DELETE,
            RepositoryOperation.CREATE_DIRECTORY,
            RepositoryOperation.CREATE_FILE,
            RepositoryOperation.RENAME,
            RepositoryOperation.SIZE,
            RepositoryOperation.SEARCH,
            RepositoryOperation.CHECKSUM,
        )

        fun local(provider: String): RepositoryCapabilities =
            RepositoryCapabilities(provider, FILE_OPERATIONS)

        fun network(provider: String, serverSideCopy: Boolean = true): RepositoryCapabilities {
            val operations = buildSet {
                addAll(setOf(
                    RepositoryOperation.CONNECT,
                    RepositoryOperation.DISCONNECT,
                    RepositoryOperation.LIST,
                    RepositoryOperation.INFO,
                    RepositoryOperation.EXISTS,
                    RepositoryOperation.MOVE,
                    RepositoryOperation.DELETE,
                    RepositoryOperation.CREATE_DIRECTORY,
                    RepositoryOperation.RENAME,
                    RepositoryOperation.DOWNLOAD,
                    RepositoryOperation.UPLOAD,
                ))
                if (serverSideCopy) add(RepositoryOperation.COPY)
            }
            return RepositoryCapabilities(provider, operations)
        }

        fun cloud(provider: String): RepositoryCapabilities = RepositoryCapabilities(
            provider = provider,
            supportedOperations = setOf(
                RepositoryOperation.AUTHENTICATE,
                RepositoryOperation.REFRESH_TOKEN,
                RepositoryOperation.SIGN_OUT,
                RepositoryOperation.LIST,
                RepositoryOperation.DOWNLOAD,
                RepositoryOperation.UPLOAD,
                RepositoryOperation.DELETE,
                RepositoryOperation.CREATE_FOLDER,
                RepositoryOperation.RENAME,
                RepositoryOperation.QUOTA,
            ),
        )

        fun unsupported(provider: String): RepositoryCapabilities =
            RepositoryCapabilities(provider, emptySet())
    }
}

fun repositoryException(
    provider: String,
    operation: RepositoryOperation,
    kind: RepositoryErrorKind,
    message: String,
    retryable: Boolean,
    statusCode: Int? = null,
    cause: Throwable? = null,
): RepositoryException = RepositoryException(
    error = RepositoryError(
        provider = provider,
        operation = operation,
        kind = kind,
        message = "$provider ${operation.name.lowercase()}: ${message.trim().take(MAX_ERROR_MESSAGE_LENGTH)}",
        retryable = retryable,
        statusCode = statusCode,
    ),
    cause = cause,
)

fun unsupportedRepositoryOperation(
    provider: String,
    operation: RepositoryOperation,
): RepositoryException = repositoryException(
    provider = provider,
    operation = operation,
    kind = RepositoryErrorKind.UNSUPPORTED,
    message = "operation is not supported",
    retryable = false,
)

fun notConnectedRepositoryException(
    provider: String,
    operation: RepositoryOperation,
): RepositoryException = repositoryException(
    provider = provider,
    operation = operation,
    kind = RepositoryErrorKind.TRANSPORT,
    message = "provider is not connected",
    retryable = true,
)

fun httpRepositoryException(
    provider: String,
    operation: RepositoryOperation,
    statusCode: Int,
    message: String = "remote request failed",
    cause: Throwable? = null,
): RepositoryException {
    val kind = when (statusCode) {
        401 -> RepositoryErrorKind.AUTHENTICATION
        403 -> RepositoryErrorKind.PERMISSION
        404 -> RepositoryErrorKind.NOT_FOUND
        409, 412 -> RepositoryErrorKind.CONFLICT
        else -> RepositoryErrorKind.TRANSPORT
    }
    val retryable = statusCode == 408 || statusCode == 425 || statusCode == 429 || statusCode >= 500
    return repositoryException(provider, operation, kind, message, retryable, statusCode, cause)
}

fun Throwable.asRepositoryException(
    provider: String,
    operation: RepositoryOperation,
    defaultKind: RepositoryErrorKind = RepositoryErrorKind.UNKNOWN,
): RepositoryException {
    if (this is CancellationException) throw this
    if (this is RepositoryException && error.provider == provider && error.operation == operation) return this

    val kind = when {
        this is AccessDeniedException || this is SecurityException -> RepositoryErrorKind.PERMISSION
        this is FileAlreadyExistsException -> RepositoryErrorKind.CONFLICT
        this is NoSuchFileException || this is FileNotFoundException -> RepositoryErrorKind.NOT_FOUND
        message.orEmpty().containsAnyIgnoreCase("authentication", "auth fail", "unauthorized", "login failed", "invalid credential") ->
            RepositoryErrorKind.AUTHENTICATION
        message.orEmpty().containsAnyIgnoreCase("permission denied", "access denied", "forbidden") ->
            RepositoryErrorKind.PERMISSION
        message.orEmpty().containsAnyIgnoreCase("malformed", "corrupt", "invalid json", "parse error") ->
            RepositoryErrorKind.CORRUPT
        message.orEmpty().containsAnyIgnoreCase("conflict", "already exists", "file exists") ->
            RepositoryErrorKind.CONFLICT
        defaultKind != RepositoryErrorKind.UNKNOWN -> defaultKind
        this is IOException -> RepositoryErrorKind.TRANSPORT
        this is IllegalArgumentException -> RepositoryErrorKind.INVALID
        else -> RepositoryErrorKind.UNKNOWN
    }
    return repositoryException(
        provider = provider,
        operation = operation,
        kind = kind,
        message = message ?: javaClass.simpleName,
        retryable = kind == RepositoryErrorKind.TRANSPORT,
        cause = this,
    )
}

fun Throwable.isMissingRepositoryResource(): Boolean {
    if (this is NoSuchFileException || this is FileNotFoundException) return true
    val text = message.orEmpty().lowercase()
    return "no such file" in text || "not found" in text || "does not exist" in text
}

fun <T> Result<T>.mapRepositoryFailure(
    provider: String,
    operation: RepositoryOperation,
): Result<T> = fold(
    onSuccess = { Result.success(it) },
    onFailure = { error -> Result.failure(error.asRepositoryException(provider, operation)) },
)

private const val MAX_ERROR_MESSAGE_LENGTH = 256

private fun String.containsAnyIgnoreCase(vararg values: String): Boolean {
    val normalized = lowercase()
    return values.any { it in normalized }
}
