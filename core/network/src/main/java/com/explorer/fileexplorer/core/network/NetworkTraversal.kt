package com.explorer.fileexplorer.core.network

import com.explorer.fileexplorer.core.model.RepositoryOperationLimits
import java.io.IOException
import java.security.MessageDigest

internal class NetworkTraversalBudget {
    private var visitedEntries = 0

    fun visit(depth: Int) {
        if (depth > RepositoryOperationLimits.MAX_NETWORK_TRAVERSAL_DEPTH) {
            throw IOException("Remote traversal depth exceeds ${RepositoryOperationLimits.MAX_NETWORK_TRAVERSAL_DEPTH}")
        }
        visitedEntries++
        if (visitedEntries > RepositoryOperationLimits.MAX_NETWORK_TRAVERSAL_ENTRIES) {
            throw IOException("Remote traversal exceeds ${RepositoryOperationLimits.MAX_NETWORK_TRAVERSAL_ENTRIES} entries")
        }
    }
}

internal fun checkedNetworkAdd(first: Long, second: Long): Long = try {
    Math.addExact(first, second)
} catch (error: ArithmeticException) {
    throw IOException("Remote size exceeds the supported range", error)
}

internal fun checksumDigest(algorithm: String): MessageDigest = try {
    MessageDigest.getInstance(algorithm)
} catch (error: Exception) {
    throw IllegalArgumentException("Unsupported checksum algorithm: $algorithm", error)
}

internal fun checksumHex(digest: MessageDigest): String =
    digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
