package com.explorer.fileexplorer.core.storage

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
interface SecureCredentialModule {
    @Binds
    fun bindCredentialCipher(cipher: SecureCredentialCipher): CredentialCipher
}
