package com.explorer.fileexplorer.core.cloud.drive

import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import com.explorer.fileexplorer.core.cloud.CloudAccount
import com.explorer.fileexplorer.core.cloud.CloudProvider
import com.explorer.fileexplorer.core.cloud.CloudService
import com.explorer.fileexplorer.core.model.FileItem
import com.google.gson.Gson
import com.google.gson.JsonObject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Google Drive provider using REST API v3.
 * OAuth must be configured in Google Cloud Console with your app's package + SHA-1.
 * Client ID should be set in BuildConfig or secrets.
 */
@Singleton
class GoogleDriveProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : CloudProvider {

    private val client = OkHttpClient.Builder().build()
    private val gson = Gson()
    private val apiBase = "https://www.googleapis.com/drive/v3"

    override val service = CloudService.GOOGLE_DRIVE
    override val isAuthenticated: Boolean = false // Check token validity

    override suspend fun getAuthIntent(): Intent? {
        // In production: use Google Sign-In SDK or Custom Tabs for OAuth
        // Returns intent to launch Google auth flow
        // The client ID and redirect URI would come from build config
        return null // Placeholder — requires Google Cloud Console setup
    }

    override suspend fun handleAuthResult(data: Intent): Result<CloudAccount> {
        // Exchange authorization code for tokens
        // POST to https://oauth2.googleapis.com/token
        return Result.failure(NotImplementedError("Configure Google Cloud Console credentials"))
    }

    override suspend fun refreshToken(account: CloudAccount): Result<CloudAccount> = withContext(Dispatchers.IO) {
        try {
            val body = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", account.refreshToken)
                // .add("client_id", BuildConfig.GOOGLE_CLIENT_ID)
                .build()
            val request = Request.Builder()
                .url("https://oauth2.googleapis.com/token")
                .post(body)
                .build()
            val response = client.newCall(request).execute()
            val json = gson.fromJson(response.body?.string(), JsonObject::class.java)
            val newToken = json.get("access_token")?.asString ?: return@withContext Result.failure(Exception("No token"))
            val expiresIn = json.get("expires_in")?.asLong ?: 3600
            Result.success(account.copy(
                accessToken = newToken,
                tokenExpiry = System.currentTimeMillis() + expiresIn * 1000,
            ))
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun signOut(account: CloudAccount): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://oauth2.googleapis.com/revoke?token=${account.accessToken}")
                .post("".toRequestBody())
                .build()
            client.newCall(request).execute()
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    override fun listFiles(account: CloudAccount, folderId: String): Flow<List<FileItem>> = flow {
        val query = "'$folderId' in parents and trashed = false"
        val fields = "files(id,name,mimeType,size,modifiedTime,parents,iconLink)"
        val url = "$apiBase/files?q=${java.net.URLEncoder.encode(query, "UTF-8")}&fields=$fields&orderBy=folder,name&pageSize=1000"

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${account.accessToken}")
            .build()

        try {
            val response = client.newCall(request).execute()
            val json = gson.fromJson(response.body?.string(), JsonObject::class.java)
            val files = json.getAsJsonArray("files")?.map { el ->
                val obj = el.asJsonObject
                val name = obj.get("name")?.asString ?: ""
                val mimeType = obj.get("mimeType")?.asString ?: ""
                val isDir = mimeType == "application/vnd.google-apps.folder"
                val size = obj.get("size")?.asLong ?: 0L
                val modified = parseGoogleDate(obj.get("modifiedTime")?.asString)
                val ext = name.substringAfterLast('.', "")
                FileItem(
                    name = name,
                    path = obj.get("id")?.asString ?: "",
                    size = size,
                    lastModified = modified,
                    isDirectory = isDir,
                    mimeType = if (isDir) "inode/directory" else mimeType,
                    extension = ext,
                )
            } ?: emptyList()
            emit(files)
        } catch (_: Exception) { emit(emptyList()) }
    }.flowOn(Dispatchers.IO)

    override suspend fun download(
        account: CloudAccount, fileId: String, localPath: String,
        onProgress: (Long, Long) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$apiBase/files/$fileId?alt=media")
                .header("Authorization", "Bearer ${account.accessToken}")
                .build()
            val response = client.newCall(request).execute()
            val totalSize = response.body?.contentLength() ?: 0L
            FileOutputStream(localPath).use { output ->
                response.body?.byteStream()?.use { input ->
                    val buf = ByteArray(65536)
                    var downloaded = 0L
                    var len: Int
                    while (input.read(buf).also { len = it } != -1) {
                        output.write(buf, 0, len)
                        downloaded += len
                        onProgress(downloaded, totalSize)
                    }
                }
            }
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun upload(
        account: CloudAccount, localPath: String, parentFolderId: String,
        onProgress: (Long, Long) -> Unit,
    ): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val file = File(localPath)
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension) ?: "application/octet-stream"

            // Metadata
            val metadata = JsonObject().apply {
                addProperty("name", file.name)
                add("parents", gson.toJsonTree(listOf(parentFolderId)))
            }

            // Multipart upload
            val metadataPart = metadata.toString().toRequestBody("application/json".toMediaType())
            val filePart = file.asRequestBody(mimeType.toMediaType())
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addPart(metadataPart)
                .addPart(filePart)
                .build()

            val request = Request.Builder()
                .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,name,mimeType,size")
                .header("Authorization", "Bearer ${account.accessToken}")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            val json = gson.fromJson(response.body?.string(), JsonObject::class.java)
            onProgress(file.length(), file.length())
            Result.success(FileItem(
                name = json.get("name")?.asString ?: file.name,
                path = json.get("id")?.asString ?: "",
                size = json.get("size")?.asLong ?: file.length(),
                mimeType = json.get("mimeType")?.asString ?: mimeType,
            ))
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun delete(account: CloudAccount, fileId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$apiBase/files/$fileId")
                .header("Authorization", "Bearer ${account.accessToken}")
                .delete()
                .build()
            client.newCall(request).execute()
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun createFolder(account: CloudAccount, name: String, parentId: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val metadata = JsonObject().apply {
                addProperty("name", name)
                addProperty("mimeType", "application/vnd.google-apps.folder")
                add("parents", gson.toJsonTree(listOf(parentId)))
            }
            val request = Request.Builder()
                .url("$apiBase/files?fields=id,name")
                .header("Authorization", "Bearer ${account.accessToken}")
                .post(metadata.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val response = client.newCall(request).execute()
            val json = gson.fromJson(response.body?.string(), JsonObject::class.java)
            Result.success(FileItem(
                name = json.get("name")?.asString ?: name,
                path = json.get("id")?.asString ?: "",
                isDirectory = true, mimeType = "inode/directory",
            ))
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun rename(account: CloudAccount, fileId: String, newName: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val body = JsonObject().apply { addProperty("name", newName) }
            val request = Request.Builder()
                .url("$apiBase/files/$fileId?fields=id,name,mimeType,size")
                .header("Authorization", "Bearer ${account.accessToken}")
                .patch(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val response = client.newCall(request).execute()
            val json = gson.fromJson(response.body?.string(), JsonObject::class.java)
            Result.success(FileItem(
                name = json.get("name")?.asString ?: newName,
                path = json.get("id")?.asString ?: fileId,
            ))
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun getQuota(account: CloudAccount): Pair<Long, Long> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$apiBase/about?fields=storageQuota")
                .header("Authorization", "Bearer ${account.accessToken}")
                .build()
            val response = client.newCall(request).execute()
            val json = gson.fromJson(response.body?.string(), JsonObject::class.java)
            val quota = json.getAsJsonObject("storageQuota")
            val total = quota?.get("limit")?.asLong ?: 0L
            val used = quota?.get("usage")?.asLong ?: 0L
            Pair(total, used)
        } catch (_: Exception) { Pair(0L, 0L) }
    }

    private fun parseGoogleDate(date: String?): Long {
        if (date == null) return 0L
        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).parse(date)?.time ?: 0L
        } catch (_: Exception) { 0L }
    }
}
