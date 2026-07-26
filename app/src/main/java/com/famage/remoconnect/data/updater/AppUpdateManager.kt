package com.famage.remoconnect.data.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class AppUpdateManager(private val context: Context) {

    // Default GitHub repo releases endpoint (can be overridden or customized)
    private val defaultUpdateServerUrl = "https://api.github.com/repos/famage/RemoConnect/releases/latest"

    /**
     * Checks if a new app version is available.
     * Compares remote versionCode / versionName against current app versionCode.
     */
    suspend fun checkForUpdates(
        currentVersionCode: Int,
        customUrl: String? = null
    ): UpdateInfo? = withContext(Dispatchers.IO) {
        val targetUrl = customUrl ?: defaultUpdateServerUrl
        val connection = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 10000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "RemoConnect-AndroidApp")
        }

        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext null
            }

            val jsonText = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(jsonText)

            val parsedInfo = parseReleaseJson(json) ?: return@withContext null

            if (parsedInfo.versionCode > currentVersionCode) {
                parsedInfo
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Helper to parse release JSON from GitHub API or a custom update endpoint.
     */
    private fun parseReleaseJson(json: JSONObject): UpdateInfo? {
        // Option A: Custom JSON format
        if (json.has("versionCode")) {
            val versionName = json.optString("versionName", "1.0")
            val versionCode = json.optInt("versionCode", 1)
            val releaseNotes = json.optString("releaseNotes", "")
            val downloadUrl = json.optString("downloadUrl", "")
            val isMandatory = json.optBoolean("isMandatory", false)
            return UpdateInfo(
                versionName = versionName,
                versionCode = versionCode,
                releaseNotes = releaseNotes,
                downloadUrl = downloadUrl,
                isMandatory = isMandatory
            )
        }

        // Option B: GitHub Releases API format
        if (json.has("tag_name")) {
            val tagName = json.getString("tag_name")
            val releaseNotes = json.optString("body", "")
            val publishedAt = json.optString("published_at", "")

            // Extract numeric version code from tag name (e.g., "v1.2" -> 2 or "v1.0.3" -> 3)
            val cleanVersionName = tagName.removePrefix("v").trim()
            val parsedVersionCode = parseVersionCodeFromName(cleanVersionName)

            // Look for .apk asset
            var apkDownloadUrl = ""
            if (json.has("assets")) {
                val assets = json.getJSONArray("assets")
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkDownloadUrl = asset.optString("browser_download_url", "")
                        break
                    }
                }
            }

            return UpdateInfo(
                versionName = cleanVersionName,
                versionCode = parsedVersionCode,
                releaseNotes = releaseNotes,
                downloadUrl = apkDownloadUrl,
                publishedAt = publishedAt
            )
        }

        return null
    }

    private fun parseVersionCodeFromName(versionName: String): Int {
        val parts = versionName.split(".")
        return try {
            if (parts.size >= 2) {
                val major = parts[0].toIntOrNull() ?: 1
                val minor = parts[1].toIntOrNull() ?: 0
                val patch = if (parts.size >= 3) parts[2].toIntOrNull() ?: 0 else 0
                (major * 100) + (minor * 10) + patch
            } else {
                versionName.toIntOrNull() ?: 1
            }
        } catch (e: Exception) {
            1
        }
    }

    /**
     * Downloads the APK file from downloadUrl and emits progress (0..100).
     */
    suspend fun downloadApk(
        updateInfo: UpdateInfo,
        onProgress: (Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.cacheDir, "apks").apply { mkdirs() }
        val apkFile = File(downloadDir, "RemoConnect_${updateInfo.versionName}.apk")

        if (apkFile.exists()) {
            apkFile.delete()
        }

        val url = URL(updateInfo.downloadUrl)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("User-Agent", "RemoConnect-AndroidApp")
            instanceFollowRedirects = true
        }

        connection.connect()

        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            throw IllegalStateException("Failed to download APK. Server returned HTTP ${connection.responseCode}")
        }

        val fileLength = connection.contentLength
        val inputStream = connection.inputStream
        val outputStream = FileOutputStream(apkFile)

        val buffer = ByteArray(8192)
        var totalBytesRead = 0L
        var bytesRead: Int

        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            outputStream.write(buffer, 0, bytesRead)
            totalBytesRead += bytesRead

            if (fileLength > 0) {
                val progress = ((totalBytesRead * 100) / fileLength).toInt()
                onProgress(progress)
            }
        }

        outputStream.flush()
        outputStream.close()
        inputStream.close()
        connection.disconnect()

        onProgress(100)
        apkFile
    }

    /**
     * Triggers installation of the downloaded APK using Android FileProvider.
     */
    fun installApk(apkFile: File) {
        if (!apkFile.exists()) return

        // On API 26+, check if the app has permission to install unknown apps
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(settingsIntent)
                return
            }
        }

        val apkUri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            apkFile
        )

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(installIntent)
    }
}
