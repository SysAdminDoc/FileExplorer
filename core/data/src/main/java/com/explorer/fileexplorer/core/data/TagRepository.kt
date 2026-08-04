package com.explorer.fileexplorer.core.data

import com.explorer.fileexplorer.core.database.TagDao
import com.explorer.fileexplorer.core.database.TagEntity
import kotlinx.coroutines.flow.Flow
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

object TagNamePolicy {
    const val MAX_LENGTH = 40

    fun normalize(value: String): Result<String> = runCatching {
        val normalized = value.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
        require(normalized.isNotEmpty()) { "Tag name cannot be blank" }
        require(normalized.length <= MAX_LENGTH) { "Tag name cannot exceed $MAX_LENGTH characters" }
        normalized
    }
}

@Singleton
class TagRepository @Inject constructor(
    private val tagDao: TagDao,
) {
    val tags: Flow<List<TagEntity>> = tagDao.getAllFlow()

    suspend fun createTag(value: String): Result<TagEntity> = TagNamePolicy.normalize(value).fold(
        onSuccess = { name ->
            if (tagDao.getByName(name) != null) {
                Result.failure(IllegalArgumentException("Tag already exists"))
            } else {
                tagDao.insert(TagEntity(name))
                Result.success(TagEntity(name))
            }
        },
        onFailure = { Result.failure(it) },
    )

    suspend fun deleteTag(tagName: String) {
        tagDao.deleteByName(tagName)
    }

    suspend fun commonTagsForPaths(paths: List<String>): Set<String> {
        if (paths.isEmpty()) return emptySet()
        return paths
            .map { tagDao.getTagNamesForPath(it).toSet() }
            .reduce(Set<String>::intersect)
    }

    suspend fun replaceTags(paths: List<String>, tagNames: Set<String>) {
        paths.distinct().forEach { path -> tagDao.replaceTags(path, tagNames.toList()) }
    }

    suspend fun pathsForAllTags(tagNames: Set<String>): Set<String> {
        if (tagNames.isEmpty()) return emptySet()
        return tagDao.getPathsWithAllTags(tagNames.toList(), tagNames.size).toSet()
    }
}
