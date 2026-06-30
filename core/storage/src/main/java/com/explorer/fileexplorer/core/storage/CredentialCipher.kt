package com.explorer.fileexplorer.core.storage

interface CredentialCipher {
    fun isEncrypted(value: String): Boolean
    fun encrypt(plainText: String): String
    fun decrypt(storedText: String): String
}
