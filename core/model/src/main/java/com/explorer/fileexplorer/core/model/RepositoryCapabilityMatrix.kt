package com.explorer.fileexplorer.core.model

import java.util.Locale

/** Broad provider families used when deriving feature-level capability status. */
enum class RepositoryProviderKind {
    LOCAL,
    SAF,
    NETWORK,
    CLOUD,
    PLUGIN,
    UNKNOWN,
}

/** User-facing features whose support cannot be inferred from one operation alone. */
enum class RepositoryFeature(val displayName: String) {
    SEARCH("Search"),
    CHECKSUMS("Checksums"),
    SECURE_DELETE("Secure delete"),
    CLOUD_SIGN_IN("Cloud sign-in"),
    ARCHIVE_EXTRACTION("Archive extraction"),
    ARCHIVE_CREATION("Archive creation"),
    WRITE("Write operations"),
    TRASH("Trash"),
}

/** Truthfulness states shared by UI, automation, and diagnostics. */
enum class CapabilityStatus {
    VERIFIED,
    EXPENSIVE,
    BEST_EFFORT,
    READ_ONLY,
    CONFIGURATION_REQUIRED,
    UNAVAILABLE,
}

data class CapabilityAssessment(
    val status: CapabilityStatus,
    val reason: String,
    val operation: RepositoryOperation? = null,
) {
    /** Whether an action may be offered to the user or automation. */
    val isActionEnabled: Boolean
        get() = status == CapabilityStatus.VERIFIED ||
            status == CapabilityStatus.EXPENSIVE ||
            status == CapabilityStatus.BEST_EFFORT
}

/**
 * One provider/location-specific source of truth for operation and feature support.
 *
 * Operation status is derived from the provider contract and cost semantics. Feature
 * status adds the cross-cutting states that require more context, such as archive
 * handling, secure deletion, and cloud authentication readiness.
 */
data class RepositoryCapabilityMatrix(
    val provider: String,
    val location: String,
    val operationStatuses: Map<RepositoryOperation, CapabilityAssessment>,
    val featureStatuses: Map<RepositoryFeature, CapabilityAssessment>,
    val providerKind: RepositoryProviderKind = RepositoryProviderKind.UNKNOWN,
) {
    fun operationStatus(operation: RepositoryOperation): CapabilityAssessment =
        operationStatuses[operation]
            ?: CapabilityAssessment(
                status = CapabilityStatus.UNAVAILABLE,
                reason = "$provider does not expose ${operation.displayName()}",
                operation = operation,
            )

    fun featureStatus(feature: RepositoryFeature): CapabilityAssessment =
        featureStatuses[feature]
            ?: CapabilityAssessment(
                status = CapabilityStatus.UNAVAILABLE,
                reason = "$provider does not expose ${feature.displayName}",
            )

    fun isActionEnabled(operation: RepositoryOperation): Boolean =
        operationStatus(operation).isActionEnabled

    fun isActionEnabled(feature: RepositoryFeature): Boolean =
        featureStatus(feature).isActionEnabled

    fun explanation(operation: RepositoryOperation): String = operationStatus(operation).reason

    fun explanation(feature: RepositoryFeature): String = featureStatus(feature).reason

    fun require(operation: RepositoryOperation): Result<Unit> = requireEnabled(
        label = operation.displayName(),
        assessment = operationStatus(operation),
    )

    fun require(feature: RepositoryFeature): Result<Unit> = requireEnabled(
        label = feature.displayName,
        assessment = featureStatus(feature),
    )

    fun withFeature(feature: RepositoryFeature, assessment: CapabilityAssessment): RepositoryCapabilityMatrix =
        copy(featureStatuses = featureStatuses + (feature to assessment))

    fun withOperation(operation: RepositoryOperation, assessment: CapabilityAssessment): RepositoryCapabilityMatrix =
        copy(operationStatuses = operationStatuses + (operation to assessment))

    val hasNonVerifiedFeature: Boolean
        get() = relevantFeatureStatuses().values.any { it.status != CapabilityStatus.VERIFIED }

    /** Concise explanatory text suitable for a browser/settings status surface. */
    fun featureSummary(): String = relevantFeatureStatuses()
        .toSortedMap(compareBy { it.ordinal })
        .filterValues { it.status != CapabilityStatus.VERIFIED }
        .map { (feature, assessment) -> "${feature.displayName}: ${assessment.reason}" }
        .joinToString(" • ")

    private fun relevantFeatureStatuses(): Map<RepositoryFeature, CapabilityAssessment> =
        featureStatuses.filterKeys { feature ->
            when (feature) {
                RepositoryFeature.CLOUD_SIGN_IN -> providerKind == RepositoryProviderKind.CLOUD
                RepositoryFeature.ARCHIVE_EXTRACTION,
                RepositoryFeature.ARCHIVE_CREATION,
                -> providerKind != RepositoryProviderKind.CLOUD
                else -> true
            }
        }

    /** Stable, redacted-enough diagnostic representation with no paths beyond the location label. */
    fun diagnosticSummary(): String {
        val operations = operationStatuses
            .toSortedMap(compareBy { it.ordinal })
            .entries
            .joinToString(",") { (operation, assessment) ->
                "${operation.name}:${assessment.status.name}"
            }
        val features = featureStatuses
            .toSortedMap(compareBy { it.ordinal })
            .entries
            .joinToString(",") { (feature, assessment) ->
                "${feature.name}:${assessment.status.name}"
            }
        return "provider=$provider location=$location operations=[$operations] features=[$features]"
    }

    companion object {
        fun from(
            capabilities: RepositoryCapabilities,
            location: String = capabilities.provider,
        ): RepositoryCapabilityMatrix {
            val operations = RepositoryOperation.entries.associateWith { operation ->
                if (!capabilities.supports(operation)) {
                    CapabilityAssessment(
                        status = CapabilityStatus.UNAVAILABLE,
                        reason = "${capabilities.provider} does not support ${operation.displayName()}",
                        operation = operation,
                    )
                } else {
                    val semantics = capabilities.semantics(operation)
                    val cost = semantics?.cost
                    CapabilityAssessment(
                        status = if (cost == RepositoryOperationCost.HIGH) {
                            CapabilityStatus.EXPENSIVE
                        } else {
                            CapabilityStatus.VERIFIED
                        },
                        reason = buildString {
                            append("${capabilities.provider} supports ${operation.displayName()}")
                            if (cost == RepositoryOperationCost.HIGH) append(" but may be expensive")
                            semantics?.limit?.takeIf { it.isNotBlank() }?.let { append("; limit: $it") }
                        },
                        operation = operation,
                    )
                }
            }

            val features = RepositoryFeature.entries.associateWith { feature ->
                when (feature) {
                    RepositoryFeature.SEARCH -> operations.getValue(RepositoryOperation.SEARCH)
                        .copy(reason = operations.getValue(RepositoryOperation.SEARCH).reason)
                    RepositoryFeature.CHECKSUMS -> operations.getValue(RepositoryOperation.CHECKSUM)
                        .copy(reason = operations.getValue(RepositoryOperation.CHECKSUM).reason)
                    RepositoryFeature.WRITE -> writeAssessment(capabilities, operations)
                    RepositoryFeature.CLOUD_SIGN_IN -> if (capabilities.kind == RepositoryProviderKind.CLOUD) {
                        CapabilityAssessment(
                            status = CapabilityStatus.CONFIGURATION_REQUIRED,
                            reason = "Cloud sign-in requires provider OAuth configuration",
                            operation = RepositoryOperation.AUTHENTICATE,
                        )
                    } else {
                        CapabilityAssessment(
                            status = CapabilityStatus.UNAVAILABLE,
                            reason = "Cloud sign-in is not a capability of ${capabilities.provider}",
                            operation = RepositoryOperation.AUTHENTICATE,
                        )
                    }
                    RepositoryFeature.ARCHIVE_EXTRACTION -> archiveAssessment(
                        capabilities,
                        location,
                        "extract archives",
                    )
                    RepositoryFeature.ARCHIVE_CREATION -> archiveAssessment(
                        capabilities,
                        location,
                        "create archives",
                    )
                    RepositoryFeature.SECURE_DELETE -> CapabilityAssessment(
                        status = CapabilityStatus.UNAVAILABLE,
                        reason = "${capabilities.provider} exposes delete only; secure overwrite is not verified",
                        operation = RepositoryOperation.DELETE,
                    )
                    RepositoryFeature.TRASH -> CapabilityAssessment(
                        status = CapabilityStatus.UNAVAILABLE,
                        reason = "Trash is not available for ${capabilities.provider}",
                    )
                }
            }
            return RepositoryCapabilityMatrix(
                provider = capabilities.provider,
                location = location,
                operationStatuses = operations,
                featureStatuses = features,
                providerKind = capabilities.kind,
            )
        }

        fun unavailable(provider: String = "unknown", location: String = provider): RepositoryCapabilityMatrix =
            from(RepositoryCapabilities.unsupported(provider), location)

        private fun writeAssessment(
            capabilities: RepositoryCapabilities,
            operations: Map<RepositoryOperation, CapabilityAssessment>,
        ): CapabilityAssessment {
            val writeOperations = setOf(
                RepositoryOperation.COPY,
                RepositoryOperation.MOVE,
                RepositoryOperation.DELETE,
                RepositoryOperation.CREATE_DIRECTORY,
                RepositoryOperation.CREATE_FILE,
                RepositoryOperation.RENAME,
                RepositoryOperation.UPLOAD,
                RepositoryOperation.CREATE_FOLDER,
            )
            val supportedWrite = writeOperations.firstOrNull { operations.getValue(it).isActionEnabled }
            return if (supportedWrite != null) {
                CapabilityAssessment(
                    status = CapabilityStatus.VERIFIED,
                    reason = "${capabilities.provider} exposes write operations",
                    operation = supportedWrite,
                )
            } else if (capabilities.supports(RepositoryOperation.LIST)) {
                CapabilityAssessment(
                    status = CapabilityStatus.READ_ONLY,
                    reason = "${capabilities.provider} can be browsed but does not expose write operations",
                )
            } else {
                CapabilityAssessment(
                    status = CapabilityStatus.UNAVAILABLE,
                    reason = "${capabilities.provider} does not expose write operations",
                )
            }
        }

        private fun archiveAssessment(
            capabilities: RepositoryCapabilities,
            location: String,
            action: String,
        ): CapabilityAssessment {
            val normalized = location.substringBefore('?').lowercase(Locale.ROOT)
            if (capabilities.kind != RepositoryProviderKind.LOCAL) {
                return CapabilityAssessment(
                    status = CapabilityStatus.UNAVAILABLE,
                    reason = "${capabilities.provider} cannot $action at this location",
                )
            }
            if (normalized.endsWith(".rar")) {
                return CapabilityAssessment(
                    status = CapabilityStatus.UNAVAILABLE,
                    reason = "RAR archives are read-only; this archive action is unavailable",
                )
            }
            return CapabilityAssessment(
                status = CapabilityStatus.VERIFIED,
                reason = "Local archive $action is verified",
            )
        }

        private fun requireEnabled(label: String, assessment: CapabilityAssessment): Result<Unit> =
            if (assessment.isActionEnabled) {
                Result.success(Unit)
            } else {
                Result.failure(UnsupportedOperationException("$label unavailable: ${assessment.reason}"))
            }
    }
}

private fun RepositoryOperation.displayName(): String = name
    .lowercase(Locale.ROOT)
    .replace('_', ' ')
