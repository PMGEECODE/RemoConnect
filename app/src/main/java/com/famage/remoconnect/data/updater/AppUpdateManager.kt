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

    // Default GitHub repo releases endpoint
    private val defaultUpdateServerUrl = "https://api.github.com/repos/PMGEECODE/RemoConnect/releases/latest"

    /**
     * Checks if a new app version is available.
     * Compares remote versionCode / versionName against current app version.
     */
    suspend fun checkForUpdates(
        currentVersionCode: Int,
        currentVersionName: String = "1.0.0",
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
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val jsonText = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(jsonText)
                val parsedInfo = parseReleaseJson(json)
                if (parsedInfo != null) {
                    val isNewerVersionName = isVersionNewer(parsedInfo.versionName, currentVersionName)
                    val isNewerVersionCode = parsedInfo.versionCode > currentVersionCode
                    if (isNewerVersionCode || isNewerVersionName) {
                        return@withContext parsedInfo
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            connection.disconnect()
        }

        // Fallback: If GitHub API fails or is rate-limited (HTTP 403 / 429), check GitHub web release redirect
        checkWebReleaseFallback(currentVersionCode, currentVersionName)
    }

    /**
     * Web fallback for checking latest release directly from GitHub web UI redirect without API rate-limit.
     */
    private suspend fun checkWebReleaseFallback(
        currentVersionCode: Int,
        currentVersionName: String
    ): UpdateInfo? = withContext(Dispatchers.IO) {
        val webUrl = "https://github.com/PMGEECODE/RemoConnect/releases/latest"
        var redirectUrl = ""
        try {
            val connection = (URL(webUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android Mobile App)")
            }
            connection.connect()

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                responseCode == 307 || responseCode == 308) {
                redirectUrl = connection.getHeaderField("Location") ?: ""
            }
            connection.disconnect()

            if (redirectUrl.isNotBlank() && redirectUrl.contains("/releases/tag/")) {
                val tagName = redirectUrl.substringAfter("/releases/tag/").trim()
                val cleanVersionName = tagName.removePrefix("v").removePrefix("V").trim()

                if (isVersionNewer(cleanVersionName, currentVersionName)) {
                    // Find actual APK filename on the release expanded_assets page
                    var apkUrl = ""
                    try {
                        val assetsUrl = "https://github.com/PMGEECODE/RemoConnect/releases/expanded_assets/$tagName"
                        val assetsConn = (URL(assetsUrl).openConnection() as HttpURLConnection).apply {
                            requestMethod = "GET"
                            connectTimeout = 10000
                            readTimeout = 10000
                            setRequestProperty("User-Agent", "Mozilla/5.0")
                        }
                        if (assetsConn.responseCode == HttpURLConnection.HTTP_OK) {
                            val html = assetsConn.inputStream.bufferedReader().use { it.readText() }
                            val apkRegex = Regex("""href="(/PMGEECODE/RemoConnect/releases/download/[^"]+\.apk)"""", RegexOption.IGNORE_CASE)
                            val match = apkRegex.find(html)
                            if (match != null) {
                                apkUrl = "https://github.com" + match.groupValues[1]
                            }
                        }
                        assetsConn.disconnect()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    if (apkUrl.isEmpty()) {
                        apkUrl = "https://github.com/PMGEECODE/RemoConnect/releases/download/$tagName/RemoConnect.apk"
                    }

                    return@withContext UpdateInfo(
                        versionName = cleanVersionName,
                        versionCode = parseVersionCodeFromName(cleanVersionName),
                        releaseNotes = "New release $tagName available on GitHub.",
                        downloadUrl = apkUrl
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        null
    }

    /**
     * Helper to parse release JSON from GitHub API or a custom update endpoint.
     */
    private fun parseReleaseJson(json: JSONObject): UpdateInfo? {
        // Option A: Custom JSON format
        if (json.has("versionCode")) {
            val versionName = json.optString("versionName", "1.0.0")
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

            val cleanVersionName = tagName.removePrefix("v").removePrefix("V").trim()
            val parsedVersionCode = parseVersionCodeFromName(cleanVersionName, releaseNotes)

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

    private fun parseVersionCodeFromName(versionName: String, body: String = ""): Int {
        // Check if release body contains explicit versionCode tag (e.g. "versionCode: 2")
        val codeRegex = Regex("""versionCode[:=]\s*(\d+)""", RegexOption.IGNORE_CASE)
        val match = codeRegex.find(body)
        if (match != null) {
            val code = match.groupValues[1].toIntOrNull()
            if (code != null) return code
        }

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

    private fun isVersionNewer(remoteVersion: String, currentVersion: String): Boolean {
        val remoteParts = remoteVersion.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = currentVersion.split(".").mapNotNull { it.toIntOrNull() }

        val maxLength = maxOf(remoteParts.size, currentParts.size)
        for (i in 0 until maxLength) {
            val remoteVal = remoteParts.getOrElse(i) { 0 }
            val currentVal = currentParts.getOrElse(i) { 0 }
            if (remoteVal > currentVal) return true
            if (remoteVal < currentVal) return false
        }
        return false
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
