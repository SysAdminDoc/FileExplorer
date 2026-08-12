package com.explorer.fileexplorer.core.data

/**
 * The non-secret settings that may cross the portable-backup boundary.
 *
 * Credential stores, security preferences, vault contents, and share-server
 * configuration deliberately have no representation here.
 */
data class PortableSettings(
    val showHidden: Boolean = false,
    val foldersFirst: Boolean = true,
    val confirmDelete: Boolean = false,
    val defaultView: String = "LIST",
    val sortField: String = "NAME",
    val sortDirection: String = "ASCENDING",
    val thumbnailSize: Int = 48,
    val thumbnailCacheSizeMb: Int = 256,
    val thumbnailCacheLocation: String = "internal",
    val themeMode: String = "SYSTEM",
    val trashTtlDays: Int = 30,
    val compactDensity: Boolean = false,
    val showDirectorySizes: Boolean = false,
    val swipeLeftAction: String = "NONE",
    val swipeRightAction: String = "NONE",
)

/** Storage boundary used by [BackupManager] without coupling core data to a feature module. */
interface PortableSettingsStore {
    suspend fun readPortableSettings(): PortableSettings

    /** Replaces all portable settings as one DataStore operation plus its compatibility mirror. */
    suspend fun replacePortableSettings(settings: PortableSettings)
}

/** Shared validation for imported and locally persisted portable settings. */
object PortableSettingsPolicy {
    const val MIN_THUMBNAIL_SIZE = 16
    const val MAX_THUMBNAIL_SIZE = 512
    const val MIN_CACHE_SIZE_MB = 32
    const val MAX_CACHE_SIZE_MB = 1_024
    const val MAX_ENUM_CHARS = 32

    private val viewValues = setOf("LIST", "GRID")
    private val sortFieldValues = setOf("NAME", "SIZE", "DATE", "TYPE")
    private val sortDirectionValues = setOf("ASCENDING", "DESCENDING")
    private val cacheLocationValues = setOf("internal", "external")
    private val themeValues = setOf("SYSTEM", "LIGHT", "DARK", "OLED", "DYNAMIC")
    private val trashTtlValues = setOf(7, 14, 30, 60, 90)
    private val swipeActionValues = setOf("NONE", "DELETE", "SHARE", "COMPRESS", "MOVE")

    fun validate(settings: PortableSettings) {
        validateEnum("defaultView", settings.defaultView, viewValues)
        validateEnum("sortField", settings.sortField, sortFieldValues)
        validateEnum("sortDirection", settings.sortDirection, sortDirectionValues)
        validateEnum("thumbnailCacheLocation", settings.thumbnailCacheLocation, cacheLocationValues)
        validateEnum("themeMode", settings.themeMode, themeValues)
        validateEnum("swipeLeftAction", settings.swipeLeftAction, swipeActionValues)
        validateEnum("swipeRightAction", settings.swipeRightAction, swipeActionValues)
        if (settings.thumbnailSize !in MIN_THUMBNAIL_SIZE..MAX_THUMBNAIL_SIZE) {
            throw BackupFormatException("Backup field 'thumbnailSize' is outside its allowed range")
        }
        if (settings.thumbnailCacheSizeMb !in MIN_CACHE_SIZE_MB..MAX_CACHE_SIZE_MB) {
            throw BackupFormatException("Backup field 'thumbnailCacheSizeMb' is outside its allowed range")
        }
        if (settings.trashTtlDays !in trashTtlValues) {
            throw BackupFormatException("Backup field 'trashTtlDays' is outside its allowed range")
        }
    }

    /** Converts stale local preference values to the current safe defaults for export. */
    fun normalized(settings: PortableSettings): PortableSettings = settings.copy(
        defaultView = settings.defaultView.takeIf(viewValues::contains) ?: "LIST",
        sortField = settings.sortField.takeIf(sortFieldValues::contains) ?: "NAME",
        sortDirection = settings.sortDirection.takeIf(sortDirectionValues::contains) ?: "ASCENDING",
        thumbnailSize = settings.thumbnailSize.coerceIn(MIN_THUMBNAIL_SIZE, MAX_THUMBNAIL_SIZE),
        thumbnailCacheSizeMb = settings.thumbnailCacheSizeMb.coerceIn(MIN_CACHE_SIZE_MB, MAX_CACHE_SIZE_MB),
        thumbnailCacheLocation = settings.thumbnailCacheLocation.takeIf(cacheLocationValues::contains) ?: "internal",
        themeMode = settings.themeMode.takeIf(themeValues::contains) ?: "SYSTEM",
        trashTtlDays = settings.trashTtlDays.takeIf(trashTtlValues::contains) ?: 30,
        swipeLeftAction = settings.swipeLeftAction.takeIf(swipeActionValues::contains) ?: "NONE",
        swipeRightAction = settings.swipeRightAction.takeIf(swipeActionValues::contains) ?: "NONE",
    )

    private fun validateEnum(name: String, value: String, allowed: Set<String>) {
        if (value.length > MAX_ENUM_CHARS || value !in allowed) {
            throw BackupFormatException("Backup field '$name' has an unsupported value")
        }
    }
}
