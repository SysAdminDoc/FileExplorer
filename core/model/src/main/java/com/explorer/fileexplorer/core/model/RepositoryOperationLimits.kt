package com.explorer.fileexplorer.core.model

/** Safety bounds shared by remote recursive operations. */
object RepositoryOperationLimits {
    const val MAX_NETWORK_TRAVERSAL_ENTRIES: Int = 100_000
    const val MAX_NETWORK_TRAVERSAL_DEPTH: Int = 64
    const val MAX_NETWORK_SEARCH_RESULTS: Int = 10_000
    const val MAX_NETWORK_CHECKSUM_BYTES: Long = 2L * 1024L * 1024L * 1024L
}
