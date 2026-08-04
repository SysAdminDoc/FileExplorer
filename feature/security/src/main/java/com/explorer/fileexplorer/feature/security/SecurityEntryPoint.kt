package com.explorer.fileexplorer.feature.security

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SecurityEntryPoint {
    fun biometricHelper(): BiometricHelper
}
