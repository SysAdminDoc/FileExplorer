package com.explorer.fileexplorer.core.network.sftp

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import net.schmizz.sshj.common.Buffer.PlainBuffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.transport.verification.OpenSSHKnownHosts
import java.io.File
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

data class SftpHostKeyChallenge(
    val hostname: String,
    val port: Int,
    val keyAlgorithm: String,
    val publicKeyBase64: String,
    val fingerprintSha256: String,
    val isChangedKey: Boolean,
) {
    companion object {
        fun from(hostname: String, port: Int, publicKey: PublicKey, isChangedKey: Boolean): SftpHostKeyChallenge {
            val publicKeyBlob = PlainBuffer().putPublicKey(publicKey).compactData
            val keyAlgorithm = KeyType.fromKey(publicKey).toString()
            val fingerprint = MessageDigest.getInstance("SHA-256").digest(publicKeyBlob)
            return SftpHostKeyChallenge(
                hostname = hostname,
                port = port,
                keyAlgorithm = keyAlgorithm,
                publicKeyBase64 = Base64.getEncoder().encodeToString(publicKeyBlob),
                fingerprintSha256 = "SHA256:${Base64.getEncoder().withoutPadding().encodeToString(fingerprint)}",
                isChangedKey = isChangedKey,
            )
        }
    }
}

class SftpHostKeyVerificationException(
    val challenge: SftpHostKeyChallenge,
) : SecurityException(
    if (challenge.isChangedKey) {
        "SFTP host key changed for ${challenge.hostname}:${challenge.port} (${challenge.fingerprintSha256})"
    } else {
        "Unknown SFTP host key for ${challenge.hostname}:${challenge.port} (${challenge.fingerprintSha256})"
    },
)

fun Throwable.sftpHostKeyChallengeOrNull(): SftpHostKeyChallenge? {
    var current: Throwable? = this
    while (current != null) {
        if (current is SftpHostKeyVerificationException) return current.challenge
        current = current.cause
    }
    return null
}

@Singleton
class SftpKnownHostsStore private constructor(
    private val knownHostsFile: File,
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(File(context.noBackupFilesDir, KNOWN_HOSTS_FILE))

    internal constructor(
        knownHostsFile: File,
        @Suppress("UNUSED_PARAMETER") testOnly: Boolean,
    ) : this(knownHostsFile)

    fun createVerifier(): HostKeyVerifier {
        return object : HostKeyVerifier {
            override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                val knownHosts = openKnownHosts()
                val existingAlgorithms = knownHosts.findExistingAlgorithms(hostname, port)
                if (knownHosts.verify(hostname, port, key)) return true

                throw SftpHostKeyVerificationException(
                    SftpHostKeyChallenge.from(
                        hostname = hostname,
                        port = port,
                        publicKey = key,
                        isChangedKey = existingAlgorithms.isNotEmpty(),
                    ),
                )
            }

            override fun findExistingAlgorithms(hostname: String, port: Int): List<String> {
                return openKnownHosts().findExistingAlgorithms(hostname, port)
            }
        }
    }

    @Synchronized
    fun trust(challenge: SftpHostKeyChallenge) {
        ensureKnownHostsFile()
        val hostMarker = knownHostsHost(challenge.hostname, challenge.port)
        val replacement = "${hostMarker} ${challenge.keyAlgorithm} ${challenge.publicKeyBase64}"
        val retainedLines = knownHostsFile.readLines()
            .filterNot { line -> knownHostsLineMatches(line, hostMarker) }
        knownHostsFile.writeText((retainedLines + replacement).joinToString(System.lineSeparator()) + System.lineSeparator())
    }

    @Synchronized
    private fun openKnownHosts(): OpenSSHKnownHosts {
        ensureKnownHostsFile()
        return OpenSSHKnownHosts(knownHostsFile)
    }

    private fun ensureKnownHostsFile() {
        knownHostsFile.parentFile?.mkdirs()
        if (!knownHostsFile.exists()) knownHostsFile.writeText("")
    }

    private fun knownHostsLineMatches(line: String, hostMarker: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains(" ")) return false
        return trimmed.substringBefore(' ')
            .split(',')
            .any { it == hostMarker }
    }

    companion object {
        private const val KNOWN_HOSTS_FILE = "sftp_known_hosts"

        fun knownHostsHost(hostname: String, port: Int): String {
            return if (port == 22) hostname else "[$hostname]:$port"
        }
    }
}
