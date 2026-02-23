package com.explorer.fileexplorer.core.cloud.onedrive

import android.content.Context
import android.content.Intent
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
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OneDrive provider using Microsoft Graph API.
 * Requires Azure AD app registration.
 */
@Singleton
class OneDriveProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : CloudProvider {

    private val client = OkHttpClient()
    private val gson = Gson()
    private val graphBase = "https://graph.microsoft.com/v1.0"

    override val service = CloudService.ONEDRIVE
    override val isAuthenticated: Boolean = false

    override suspend fun getAuthIntent(): Intent? = null // Placeholder for MSAL
    override suspend fun handleAuthResult(data: Intent): Result<CloudAccount> =
        Result.failure(NotImplementedError("Configure Azure AD app registration"))

    override suspend fun refreshToken(account: CloudAccount): Result<CloudAccount> = withContext(Dispatchers.IO) {
        try {
            val body = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", account.refreshToken)
                .add("scope", "Files.ReadWrite.All offline_access")
                .build()
            val request = Request.Builder()
                .url("https://login.microsoftonline.com/common/oauth2/v2.0/token")
                .post(body).build()
            val response = client.newCall(request).execute()
            val json = gson.fromJson(response.body?.string(), JsonObject::class.java)
            Result.success(account.copy(
                accessToken = json.get("access_token")?.asString ?: account.accessToken,
                refreshToken = json.get("refresh_token")?.asString ?: account.refreshToken,
                tokenExpiry = System.currentTimeMillis() + (json.get("expires_in")?.asLong ?: 3600) * 1000,
            ))
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun signOut(account: CloudAccount): Result<Unit> = Result.success(Unit)

    override fun listFiles(account: CloudAccount, folderId: String): Flow<List<FileItem>> = flow {
        val path = if (folderId == "root") "/me/drive/root/children" else "/me/drive/items/$folderId/children"
        val url = "$graphBase$path?\$select=id,name,size,lastModifiedDateTime,folder,file&\$top=1000"
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer ${account.accessToken}").build()
        try {
            val response = client.newCall(request).execute()
            val json = gson.fromJson(response.body?.string(), JsonObject::class.java)
            val items = json.getAsJsonArray("value")?.map { el ->
                val obj = el.asJsonObject
                val name = obj.get("name")?.asString ?: ""
                val isDir = obj.has("folder")
                val size = obj.get("size")?.asLong ?: 0L
                val mime = obj.getAsJsonObject("file")?.get("mimeType")?.asString
                val ext = name.substringAfterLast('.', "")
                FileItem(
                    name = name, path = obj.get("id")?.asString ?: "",
                    size = size, isDirectory = isDir,
                    lastModified = parseDate(obj.get("lastModifiedDateTime")?.asString),
                    mimeType = if (isDir) "inode/directory" else mime ?: guessMime(ext),
                    extension = ext,
                )
            } ?: emptyList()
            emit(items)
        } catch (_: Exception) { emit(emptyList()) }
    }.flowOn(Dispatchers.IO)

    override suspend fun download(
        account: CloudAccount, fileId: String, localPath: String,
        onProgress: (Long, Long) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$graphBase/me/drive/items/$fileId/content")
                .header("Authorization", "Bearer ${account.accessToken}").build()
            val response = client.newCall(request).execute()
            val total = response.body?.contentLength() ?: 0L
            FileOutputStream(localPath).use { out ->
                response.body?.byteStream()?.use { input ->
                    val buf = ByteArray(65536); var dl = 0L; var len: Int
                    while (input.read(buf).also { len = it } != -1) { out.write(buf, 0, len); dl += len; onProgress(dl, total) }
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
            val parent = if (parentFolderId == "root") "root" else parentFolderId
            val url = "$graphBase/me/drive/items/$parent:/${file.name}:/content"
            val request = Request.Builder().url(url)
                .header("Authorization", "Bearer ${account.accessToken}")
                .put(file.readBytes().toRequestBody("application/octet-stream".toMediaType())).build()
            val response = client.newCall(request).execute()
            val json = gson.fromJson(response.body?.string(), JsonObject::class.java)
            onProgress(file.length(), file.length())
            Result.success(FileItem(
                name = json.get("name")?.asString ?: file.name,
                path = json.get("id")?.asString ?: "", size = file.length(),
            ))
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun delete(account: CloudAccount, fileId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$graphBase/me/drive/items/$fileId")
                .header("Authorization", "Bearer ${account.accessToken}").delete().build()
            client.newCall(request).execute()
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun createFolder(account: CloudAccount, name: String, parentId: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val parent = if (parentId == "root") "root" else parentId
            val body = JsonObject().apply {
                addProperty("name", name)
                add("folder", JsonObject())
                addProperty("@microsoft.graph.conflictBehavior", "rename")
            }
            val request = Request.Builder().url("$graphBase/me/drive/items/$parent/children")
                .header("Authorization", "Bearer ${account.accessToken}")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            val response = client.newCall(request).execute()
            val json = gson.fromJson(response.body?.string(), JsonObject::class.java)
            Result.success(FileItem(name = name, path = json.get("id")?.asString ?: "", isDirectory = true, mimeType = "inode/directory"))
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun rename(account: CloudAccount, fileId: String, newName: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val body = JsonObject().apply { addProperty("name", newName) }
            val request = Request.Builder().url("$graphBase/me/drive/items/$fileId")
                .header("Authorization", "Bearer ${account.accessToken}")
                .patch(body.toString().toRequestBody("application/json".toMediaType())).build()
            val response = client.newCall(request).execute()
            val json = gson.fromJson(response.body?.string(), JsonObject::class.java)
            Result.success(FileItem(name = newName, path = json.get("id")?.asString ?: fileId))
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun getQuota(account: CloudAccount): Pair<Long, Long> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$graphBase/me/drive?select=quota")
                .header("Authorization", "Bearer ${account.accessToken}").build()
            val response = client.newCall(request).execute()
            val json = gson.fromJson(response.body?.string(), JsonObject::class.java)
            val quota = json.getAsJsonObject("quota")
            Pair(quota?.get("total")?.asLong ?: 0L, quota?.get("used")?.asLong ?: 0L)
        } catch (_: Exception) { Pair(0L, 0L) }
    }

    private fun parseDate(date: String?): Long {
        if (date == null) return 0L
        return try { java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).parse(date)?.time ?: 0L }
        catch (_: Exception) { 0L }
    }

    private fun guessMime(ext: String): String =
        android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase()) ?: "application/octet-stream"
}
