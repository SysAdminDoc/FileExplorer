package com.explorer.fileexplorer.plugin

import android.os.Bundle
import android.os.Parcel

/** Resource and transport budgets applied to every third-party plugin call. */
object PluginLimits {
    const val MAX_CONCURRENT_CALLS = 4
    const val BIND_TIMEOUT_MS = 10_000L
    const val CALL_TIMEOUT_MS = 15_000L
    const val MAX_REQUEST_BYTES = 256 * 1024
    const val MAX_RESPONSE_BYTES = 512 * 1024
    const val MAX_PATHS = 512
    const val MAX_STRING_CHARS = 16_384
    const val MAX_QUERY_CHARS = 4_096
}

object PluginResourcePolicy {
    fun validateRequest(request: Bundle) {
        val operation = request.getString(PluginContract.KEY_OPERATION)
            ?: throw PluginCallException(PluginFailureKind.INVALID_REQUEST, "Plugin request omitted its operation")
        if (PluginCapability.requiredFor(operation) == null) {
            throw PluginCallException(
                PluginFailureKind.CAPABILITY_DENIED,
                "Plugin operation is not supported by this protocol",
            )
        }

        val paths = buildList {
            request.getString(PluginContract.KEY_PATH)?.let(::add)
            request.getStringArrayList(PluginContract.KEY_PATHS)?.let(::addAll)
            request.getString(PluginContract.KEY_DESTINATION)?.let(::add)
            request.getString(PluginContract.KEY_NEW_NAME)?.let(::add)
        }
        validatePathInputs(
            paths = paths,
            query = request.getString(PluginContract.KEY_QUERY),
            algorithm = request.getString(PluginContract.KEY_ALGORITHM),
        )

        val size = bundleSizeBytes(request)
        if (size < 0) {
            throw PluginCallException(PluginFailureKind.INVALID_REQUEST, "Plugin request could not be encoded")
        }
        if (size > PluginLimits.MAX_REQUEST_BYTES) {
            throw PluginCallException(PluginFailureKind.RESOURCE_LIMIT, "Plugin request exceeds its payload budget")
        }
    }

    fun validatePathInputs(paths: List<String>, query: String?, algorithm: String?) {
        if (paths.size > PluginLimits.MAX_PATHS) {
            throw PluginCallException(PluginFailureKind.RESOURCE_LIMIT, "Plugin request contains too many paths")
        }
        if (paths.any { it.length > PluginLimits.MAX_STRING_CHARS }) {
            throw PluginCallException(PluginFailureKind.RESOURCE_LIMIT, "Plugin request contains an overlong path")
        }
        if (query.orEmpty().length > PluginLimits.MAX_QUERY_CHARS) {
            throw PluginCallException(PluginFailureKind.RESOURCE_LIMIT, "Plugin search query is too large")
        }
        if (algorithm.orEmpty().length > 64) {
            throw PluginCallException(PluginFailureKind.RESOURCE_LIMIT, "Plugin checksum algorithm is too large")
        }
    }

    fun validateResponse(response: Bundle) {
        val size = bundleSizeBytes(response)
        if (size < 0) {
            throw PluginCallException(PluginFailureKind.INVALID_RESPONSE, "Plugin response could not be decoded")
        }
        if (size > PluginLimits.MAX_RESPONSE_BYTES) {
            throw PluginCallException(PluginFailureKind.RESOURCE_LIMIT, "Plugin response exceeds its payload budget")
        }
    }

    /** Marshals without retaining plugin-owned parcelables in the host process. */
    private fun bundleSizeBytes(bundle: Bundle): Int {
        val parcel = Parcel.obtain()
        return try {
            bundle.writeToParcel(parcel, 0)
            parcel.dataSize()
        } catch (_: RuntimeException) {
            -1
        } finally {
            parcel.recycle()
        }
    }
}
