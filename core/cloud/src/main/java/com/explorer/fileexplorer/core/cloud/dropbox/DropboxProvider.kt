package com.explorer.fileexplorer.core.cloud.dropbox

import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import com.explorer.fileexplorer.core.cloud.CloudAccount
import com.explorer.fileexplorer.core.cloud.CloudProvider
import com.explorer.fileexplorer.core.cloud.CloudService
import com.explorer.fileexplorer.core.model.FileItem
import com.google.gson.Gson
import com.google.gson.JsonObject
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
 * Dropbox provider using HTTP API v2.
 * Requires App Key from Dropbox App Console.
 */
@Singleton
class DropboxProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : CloudProvider {

    private val client = OkHttpClient()
    private val gson = Gson()
    private val apiBase = "https://api.dropboxapi.com/2"
    private val contentBase = "https://content.dropboxapi.com/2"

    override val service = CloudService.DROPBOX
    override val isAuthenticated: Boolean = false

    override suspend fun getAuthIntent(): Intent? {
        // Would use Dropbox OAuth2 PKCE flow via Custom Tabs
        // val url = "https://www.dropbox.com/oauth2/authorize?client_id=APP_KEY&response_type=code&token_access_type=offline"
        return null // Placeholder — requires Dropbox App Console setup
    }

    override suspend fun handleAuthResult(data: Intent): Result<CloudAccount> {
        return Result.failure(NotImplementedError("Configure Dropbox App Console credentials"))
    }

    override suspend fun refreshToken(account: CloudAccount): Result<CloudAccount> = withContext(Dispatchers.IO) {
        try {
            val body = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", account.refreshToken)
                // .add("client_id", BuildConfig.DROPBOX_APP_KEY)
                .build()
            val request = Request.Builder()
                .url("https://api.dropboxapi.com/oauth2/token")
                .post(body).build()
            val response = client.newCall(request).execute()
            val json = gson.fromJson(response.body?.string(), JsonObject::class.java)
            Result.success(account.copy(
                accessToken = json.get("access_token")?.asString ?: account.accessToken,
                tokenExpiry = System.currentTimeMillis() + (json.get("expires_in")?.asLong ?: 3600) * 1000,
            ))
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun signOut(account: CloudAccount): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$apiBase/auth/token/revoke")
                .header("Authorization", "Bearer ${account.accessToken}")
                .post("null".toRequestBody("application/json".toMediaType())).build()
            client.newCall(request).execute()
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    override fun listFiles(account: CloudAccount, folderId: String): Flow<List<FileItem>> = flow {
        val path = if (folderId == "root" || folderId.isEmpty()) "" else folderId
        val body = JsonObject().apply { addProperty("path", path); addProperty("limit", 2000) }
        val request = Request.Builder().url("$apiBase/files/list_folder")
            .header("Authorization", "Bearer ${account.accessToken}")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        try {
            val response = client.newCall(request).execute()
            val json = gson.fromJson(response.body?.string(), JsonObject::class.java)
            val entries = json.getAsJsonArray("entries")?.map { el ->
                val obj = el.asJsonObject
                val tag = obj.get(".tag")?.asString ?: ""
                val name = obj.get("name")?.asString ?: ""
                val isDir = tag == "folder"
                val size = obj.get("size")?.asLong ?: 0L
                val ext = name.substringAfterLast('.', "")
                FileItem(
                    name = name,
                    path = obj.get("path_lower")?.asString ?: obj.get("id")?.asString ?: "",
                    size = size, isDirectory = isDir,
                    lastModified = parseDropboxDate(obj.get("server_modified")?.asString),
                    mimeType = if (isDir) "inode/directory" else guessMime(ext),
                    extension = ext,
                )
            } ?: emptyList()
            emit(entries)
        } catch (_: Exception) { emit(emptyList()) }
    }.flowOn(Dispatchers.IO)

    override suspend fun download(
        account: CloudAccount, fileId: String, localPath: String,
        onProgress: (Long, Long) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val arg = JsonObject().apply { addProperty("path", fileId) }
            val request = Request.Builder().url("$contentBase/files/download")
                .header("Authorization", "Bearer ${account.accessToken}")
                .header("Dropbox-API-Arg", arg.toString())
                .post("".toRequestBody()).build()
            val response = client.newCall(request).execute()
            val total = response.body?.contentLength() ?: 0L
            FileOutputStream(localPath).use { out ->
                response.body?.byteStream()?.use { input ->
                    val buf = ByteArray(65536); var dl = 0L; var len: Int
                    while (input.read(buf).also { len = it } != -1) {
                        out.write(buf, 0, len); dl += len; onProgress(dl, total)
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
            val parent = if (parentFolderId == "root") "" else parentFolderId
            val arg = JsonObject().apply {
                addProperty("path", "$parent/${file.name}")
                addProperty("mode", "add"); addProperty("autorename", true)
            }
            val request = Request.Builder().url("$contentBase/files/upload")
                .header("Authorization", "Bearer ${account.accessToken}")
                .header("Dropbox-API-Arg", arg.toString())
                .header("Content-Type", "application/octet-stream")
                .post(file.readBytes().toRequestBody("application/octet-stream".toMediaType())).build()
            val response = client.newCall(request).execute()
            val json = gson.fromJson(response.body?.string(), JsonObject::class.java)
            onProgress(file.length(), file.length())
            Result.success(FileItem(
                name = json.get("name")?.asString ?: file.name,
                path = json.get("path_lower")?.asString ?: "",
                size = json.get("size")?.asLong ?: file.length(),
            ))
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun delete(account: CloudAccount, fileId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val body = JsonObject().apply { addProperty("path", fileId) }
            val request = Request.Builder().url("$apiBase/files/delete_v2")
                .header("Authorization", "Bearer ${account.accessToken}")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            client.newCall(request).execute()
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun createFolder(account: CloudAccount, name: String, parentId: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val parent = if (parentId == "root") "" else parentId
            val body = JsonObject().apply { addProperty("path", "$parent/$name"); addProperty("autorename", false) }
            val request = Request.Builder().url("$apiBase/files/create_folder_v2")
                .header("Authorization", "Bearer ${account.accessToken}")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            val response = client.newCall(request).execute()
            val json = gson.fromJson(response.body?.string(), JsonObject::class.java)
            val metadata = json.getAsJsonObject("metadata")
            Result.success(FileItem(
                name = metadata?.get("name")?.asString ?: name,
                path = metadata?.get("path_lower")?.asString ?: "$parent/$name",
                isDirectory = true, mimeType = "inode/directory",
            ))
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun rename(account: CloudAccount, fileId: String, newName: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val parent = fileId.substringBeforeLast('/')
            val newPath = "$parent/$newName"
            val body = JsonObject().apply { addProperty("from_path", fileId); addProperty("to_path", newPath) }
            val request = Request.Builder().url("$apiBase/files/move_v2")
                .header("Authorization", "Bearer ${account.accessToken}")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            val response = client.newCall(request).execute()
            val json = gson.fromJson(response.body?.string(), JsonObject::class.java)
            val metadata = json.getAsJsonObject("metadata")
            Result.success(FileItem(name = newName, path = metadata?.get("path_lower")?.asString ?: newPath))
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun getQuota(account: CloudAccount): Pair<Long, Long> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$apiBase/users/get_space_usage")
                .header("Authorization", "Bearer ${account.accessToken}")
                .post("null".toRequestBody("application/json".toMediaType())).build()
            val response = client.newCall(request).execute()
            val json = gson.fromJson(response.body?.string(), JsonObject::class.java)
            val used = json.get("used")?.asLong ?: 0L
            val alloc = json.getAsJsonObject("allocation")?.get("allocated")?.asLong ?: 0L
            Pair(alloc, used)
        } catch (_: Exception) { Pair(0L, 0L) }
    }

    private fun parseDropboxDate(date: String?): Long {
        if (date == null) return 0L
        return try { java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).parse(date)?.time ?: 0L }
        catch (_: Exception) { 0L }
    }

    private fun guessMime(ext: String): String =
        android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase()) ?: "application/octet-stream"
}
