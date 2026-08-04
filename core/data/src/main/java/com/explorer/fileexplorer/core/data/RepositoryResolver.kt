package com.explorer.fileexplorer.core.data

fun interface RepositoryResolver {
    fun resolve(scheme: String): FileRepository?
}
