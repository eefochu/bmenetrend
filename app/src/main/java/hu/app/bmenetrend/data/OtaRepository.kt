package hu.app.bmenetrend.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class OtaRepository(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
) {
    suspend fun fetchUpdateInfo(channel: UpdateChannel): OtaUpdateInfo = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(channel.url)
            .header("Cache-Control", "no-cache")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}: ${response.message}")
            val body = response.body?.string() ?: throw IOException("Empty response")
            parseJson(body)
        }
    }

    suspend fun downloadApk(
        url: String,
        targetFile: File,
        onProgress: (Float) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Download failed with HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty response body")
            val contentLength = body.contentLength()
            targetFile.parentFile?.mkdirs()
            if (targetFile.exists()) targetFile.delete()

            body.byteStream().use { input ->
                targetFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead = 0L
                    var read: Int
                    var lastProgress = 0f
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesRead += read
                        if (contentLength > 0) {
                            val progress = (bytesRead.toFloat() / contentLength.toFloat()).coerceIn(0f, 1f)
                            if (progress - lastProgress >= 0.02f || progress >= 1f) {
                                lastProgress = progress
                                withContext(Dispatchers.Main) { onProgress(progress) }
                            }
                        }
                    }
                }
            }
        }
        targetFile
    }

    fun installApk(apkFile: File) {
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(settingsIntent)
            } else {
                throw e
            }
        }
    }

    companion object {
        fun parseJson(jsonString: String): OtaUpdateInfo {
            val json = JSONObject(jsonString)
            val tag = json.optString("tag", "")
            val version = json.optString("version", "")
            val apkRaw = json.optString("apk", "").trim()
            val apkUrl = if (apkRaw.isNotBlank() && !apkRaw.equals("N/A", ignoreCase = true) &&
                (apkRaw.startsWith("http://", ignoreCase = true) || apkRaw.startsWith("https://", ignoreCase = true))
            ) apkRaw else null
            val lastCommit = json.optString("lastCommit", "").trim().takeIf { it.isNotBlank() && !it.equals("N/A", ignoreCase = true) }
            val changelog = json.optString("changelog", "").trim().takeIf { it.isNotBlank() }
            return OtaUpdateInfo(
                tag = tag,
                version = version,
                apkUrl = apkUrl,
                lastCommit = lastCommit,
                changelog = changelog
            )
        }
    }
}
