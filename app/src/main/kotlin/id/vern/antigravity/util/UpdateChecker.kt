package id.vern.antigravity.util

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import id.vern.antigravity.R
import id.vern.antigravity.ui.MainActivity
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class ChangelogType {
    FEATURE,      // ✨ New Features
    FIX,          // 🐛 Bug Fixes
    IMPROVEMENT,  // ⚡ Improvements & Performance
    SECURITY,     // 🔒 Security & Auth
    GENERAL       // 📝 General changes
}

data class ChangelogItem(
    val category: ChangelogType,
    val title: String,
    val description: String = ""
)

data class ReleaseChangelog(
    val version: String,
    val title: String,
    val publishedDate: String,
    val rawBody: String = "",
    val items: List<ChangelogItem> = emptyList()
)

class UpdateChecker(private val context: Context) {
    private val TAG = "UpdateChecker"

    companion object {
        const val OTA_NOTIFICATION_CHANNEL_ID = "ota_updates_channel"
        const val OTA_NOTIFICATION_ID = 1009
        const val GITHUB_OWNER = "KuatoDev"
        const val GITHUB_REPO = "Antigravity"
        private const val PREFS_NAME = "antigravity_ota_prefs"
        private const val PREF_LAST_NOTIFIED = "last_notified_ota_version"
        private const val PREF_LAST_SNOOZED_TIME = "last_snoozed_ota_time"
    }

    data class RawRelease(
        val version: String,
        val title: String,
        val publishedDate: String,
        val body: String,
        val apkAssetSize: Long,
        val apkDownloadUrl: String,
        val apkFileName: String
    )

    private fun fetchGitHubApi(apiUrl: String): Pair<Int, String?> {
        return try {
            val url = URL(apiUrl)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "Antigravity-OTA-Checker")
                connectTimeout = 15000
                readTimeout = 15000
            }
            val code = connection.responseCode
            val text = if (code in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
            }
            connection.disconnect()
            Pair(code, text)
        } catch (e: Exception) {
            Pair(-1, e.localizedMessage ?: e.javaClass.simpleName)
        }
    }

    suspend fun checkForUpdates(
        currentVersion: String,
        isManualCheck: Boolean = false
    ): AppUpdateState? = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        if (!isManualCheck) {
            val lastSnoozed = prefs.getLong(PREF_LAST_SNOOZED_TIME, 0L)
            if (System.currentTimeMillis() - lastSnoozed < 24 * 60 * 60 * 1000L) {
                Log.d(TAG, "OTA check skipped: snoozed within last 24h")
                return@withContext null
            }
        }

        val candidateOwners = listOf("AltafYafai", GITHUB_OWNER)
        val repo = GITHUB_REPO

        val validReleases = mutableListOf<RawRelease>()

        for (owner in candidateOwners) {
            val listUrl = "https://api.github.com/repos/$owner/$repo/releases?per_page=30"
            val (codeList, bodyList) = fetchGitHubApi(listUrl)
            if (codeList == HttpURLConnection.HTTP_OK && !bodyList.isNullOrBlank()) {
                try {
                    val array = JSONArray(bodyList)
                    for (i in 0 until array.length()) {
                        val r = array.getJSONObject(i)
                        if (r.optBoolean("draft", false)) continue

                        val tagName = r.optString("tag_name", "").trim()
                        if (tagName.isBlank()) continue

                        val assets = r.optJSONArray("assets") ?: continue
                        var apkUrl = ""
                        var apkName = ""
                        var apkSize = 0L

                        for (j in 0 until assets.length()) {
                            val a = assets.getJSONObject(j)
                            val name = a.optString("name", "")
                            if (name.endsWith(".apk", ignoreCase = true)) {
                                val bUrl = a.optString("browser_download_url", "")
                                val aUrl = a.optString("url", "")
                                apkUrl = if (bUrl.isNotBlank()) bUrl else aUrl
                                apkName = name
                                apkSize = a.optLong("size", 0L)
                                break
                            }
                        }

                        if (apkUrl.isNotBlank()) {
                            val publishedAt = r.optString("published_at", "")
                            val releaseName = r.optString("name", tagName)
                            val body = r.optString("body", "")

                            val rel = RawRelease(
                                version = tagName,
                                title = releaseName,
                                publishedDate = publishedAt,
                                body = body,
                                apkAssetSize = apkSize,
                                apkDownloadUrl = apkUrl,
                                apkFileName = apkName.ifBlank { "Antigravity-$tagName.apk" }
                            )
                            if (validReleases.none { it.version == rel.version }) {
                                validReleases.add(rel)
                            }
                        }
                    }
                    if (validReleases.isNotEmpty()) break
                } catch (e: Exception) {
                    Log.w(TAG, "Error parsing releases for $owner: ${e.message}")
                }
            }
        }

        if (validReleases.isEmpty()) {
            if (isManualCheck) {
                return@withContext AppUpdateState.UpToDate(
                    currentVersion = currentVersion,
                    latestReleaseTitle = "Antigravity v$currentVersion",
                    latestReleaseDate = "Latest"
                )
            }
            return@withContext null
        }

        validReleases.sortWith { a, b ->
            when {
                isNewerVersion(a.version, b.version) -> -1
                isNewerVersion(b.version, a.version) -> 1
                else -> 0
            }
        }

        val latest = validReleases.first()
        val hasUpdate = isNewerVersion(currentVersion, latest.version)

        if (hasUpdate) {
            val intervening = validReleases.filter { isNewerVersion(currentVersion, it.version) }
            val structuredChangelogs = intervening.map { rel ->
                ReleaseChangelog(
                    version = rel.version,
                    title = rel.title,
                    publishedDate = formatReleaseDate(rel.publishedDate),
                    rawBody = rel.body,
                    items = parseChangelogBody(rel.body)
                )
            }

            val fullFormattedBody = intervening.joinToString("\n\n") { rel ->
                "### ${rel.title.ifBlank { rel.version }}\n${rel.body.trim()}"
            }

            val updateInfo = UpdateInfo(
                currentVersion = currentVersion,
                version = latest.version,
                releaseTitle = latest.title.ifBlank { "Antigravity ${latest.version}" },
                publishedDate = formatReleaseDate(latest.publishedDate),
                fileSizeFormatted = formatFileSize(latest.apkAssetSize),
                fileSizeBytes = latest.apkAssetSize,
                downloadUrl = latest.apkDownloadUrl,
                fileName = latest.apkFileName,
                changelogText = fullFormattedBody,
                versionChangelogs = structuredChangelogs
            )

            showUpdateNotification(updateInfo)
            return@withContext AppUpdateState.Available(updateInfo)
        } else {
            if (isManualCheck) {
                val highlights = parseChangelogBody(latest.body).take(5).map {
                    if (it.title.isNotBlank()) "${it.title}: ${it.description}" else it.description
                }
                return@withContext AppUpdateState.UpToDate(
                    currentVersion = currentVersion,
                    latestReleaseTitle = latest.title,
                    latestReleaseDate = formatReleaseDate(latest.publishedDate),
                    recentHighlights = highlights
                )
            }
            return@withContext null
        }
    }

    fun snoozeUpdate() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putLong(PREF_LAST_SNOOZED_TIME, System.currentTimeMillis()).apply()
        Log.i(TAG, "OTA update snoozed for 24 hours")
    }

    fun parseChangelogBody(body: String): List<ChangelogItem> {
        val items = mutableListOf<ChangelogItem>()
        if (body.isBlank()) return items

        val lines = body.lines().map { it.trim() }.filter { it.isNotBlank() }

        for (line in lines) {
            if (line.startsWith("#")) continue
            val bulletMatch = Regex("^[-*•]\\s*(.+)$").find(line) ?: continue
            val content = bulletMatch.groupValues[1].trim()

            val lower = content.lowercase(Locale.ROOT)
            if (lower.startsWith("direct download") ||
                lower.startsWith("checksums") ||
                lower.startsWith("signed with release key") ||
                lower.startsWith("see attached") ||
                lower.startsWith("http://") ||
                lower.startsWith("https://") ||
                lower.startsWith("[")
            ) {
                continue
            }

            var title = ""
            var desc = content
            val titleMatch = Regex("^\\*\\*(.+?)\\*\\*:\\s*(.*)$").find(content)
            if (titleMatch != null) {
                title = titleMatch.groupValues[1].trim()
                desc = titleMatch.groupValues[2].trim()
            } else {
                val colonIdx = content.indexOf(":")
                if (colonIdx in 3..35 && !content.substring(0, colonIdx).contains(" ")) {
                    title = content.substring(0, colonIdx).trim()
                    desc = content.substring(colonIdx + 1).trim()
                }
            }

            desc = desc.replace(Regex("\\[(.+?)\\]\\(.+?\\)"), "$1").replace("`", "").trim()
            if (desc.isBlank() && title.isNotBlank()) {
                desc = title
                title = ""
            }

            val combined = "$title $desc".lowercase(Locale.ROOT)
            val category = when {
                combined.contains("fix") || combined.contains("resolve") || combined.contains("bug") ||
                        combined.contains("issue") || combined.contains("error") || combined.contains("crash") -> ChangelogType.FIX
                combined.contains("feat") || combined.contains("add") || combined.contains("new") ||
                        combined.contains("support") || combined.contains("inbuilt") || combined.contains("updater") ||
                        combined.contains("dialog") || combined.contains("authorship") -> ChangelogType.FEATURE
                combined.contains("auth") || combined.contains("token") || combined.contains("login") ||
                        combined.contains("credential") || combined.contains("security") || combined.contains("oauth") -> ChangelogType.SECURITY
                combined.contains("perf") || combined.contains("optimi") || combined.contains("speed") ||
                        combined.contains("fast") || combined.contains("clean") || combined.contains("sync") ||
                        combined.contains("improve") -> ChangelogType.IMPROVEMENT
                else -> ChangelogType.GENERAL
            }

            if (title.isNotBlank() || desc.isNotBlank()) {
                items.add(ChangelogItem(category = category, title = title, description = desc))
            }
        }
        return items
    }

    fun formatReleaseDate(rawDate: String): String {
        return try {
            val instant = Instant.parse(rawDate)
            val formatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())
                .withZone(ZoneId.systemDefault())
            formatter.format(instant)
        } catch (e: Exception) {
            rawDate.take(10)
        }
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "Unknown size"
        val mb = bytes.toDouble() / (1024 * 1024)
        return String.format(Locale.US, "%.1f MB", mb)
    }

    fun showUpdateNotification(updateInfo: UpdateInfo, force: Boolean = false) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val lastNotifiedVersion = prefs.getString(PREF_LAST_NOTIFIED, "") ?: ""
            if (!force && lastNotifiedVersion == updateInfo.version) {
                return
            }

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channelName = context.getString(R.string.ota_notification_channel_name)
                val channelDesc = context.getString(R.string.ota_notification_channel_desc)
                val channel = NotificationChannel(OTA_NOTIFICATION_CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_HIGH).apply {
                    description = channelDesc
                    enableLights(true)
                    enableVibration(true)
                }
                notificationManager.createNotificationChannel(channel)
            }

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                OTA_NOTIFICATION_ID,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            )

            val title = context.getString(R.string.ota_notification_title, updateInfo.version)
            val content = context.getString(R.string.ota_notification_desc, updateInfo.version)

            val bigText = buildString {
                append(content)
                if (updateInfo.changelogText.isNotBlank()) {
                    append("\n\n")
                    append(updateInfo.changelogText.take(300))
                }
            }

            val notification = NotificationCompat.Builder(context, OTA_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(content)
                .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            notificationManager.notify(OTA_NOTIFICATION_ID, notification)
            prefs.edit().putString(PREF_LAST_NOTIFIED, updateInfo.version).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post OTA update notification", e)
        }
    }

    fun startDownload(info: UpdateInfo): Long {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val effectiveUrl = info.downloadUrl
        val rawFileName = if (info.fileName.isNotBlank()) {
            info.fileName
        } else {
            effectiveUrl.substringAfterLast("/").substringBefore("?").ifBlank { "antigravity-update-${info.version}.apk" }
        }
        val targetFileName = if (rawFileName.endsWith(".apk", ignoreCase = true)) rawFileName else "$rawFileName.apk"

        val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (downloadDir != null) {
            val existingFile = File(downloadDir, targetFileName)
            if (existingFile.exists()) {
                try { existingFile.delete() } catch (e: Exception) {}
            }
        }

        val request = DownloadManager.Request(Uri.parse(effectiveUrl)).apply {
            setTitle(context.getString(R.string.ota_download_title, targetFileName))
            setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, targetFileName)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setMimeType("application/vnd.android.package-archive")
        }
        return dm.enqueue(request)
    }

    fun cancelDownload(downloadId: Long) {
        try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.remove(downloadId)
            Log.i(TAG, "Successfully cancelled OTA download ID $downloadId")
        } catch (e: Exception) {
            Log.w(TAG, "Failed cancelling download $downloadId: ${e.message}")
        }
    }

    fun installApk(downloadId: Long): Boolean {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    Toast.makeText(context, context.getString(R.string.ota_permission_dialog_msg), Toast.LENGTH_LONG).show()
                    return false
                }
            }

            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            var contentUri: Uri? = null

            try {
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = dm.query(query)
                var localUriString: String? = null
                if (cursor != null && cursor.moveToFirst()) {
                    val localUriIdx = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                    if (localUriIdx >= 0) {
                        localUriString = cursor.getString(localUriIdx)
                    }
                    cursor.close()
                }

                if (!localUriString.isNullOrBlank()) {
                    val file = if (localUriString.startsWith("file://")) {
                        File(Uri.parse(localUriString).path ?: "")
                    } else {
                        File(localUriString)
                    }
                    if (file.exists() && file.length() > 0) {
                        contentUri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            file
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed resolving FileProvider URI: ${e.message}")
            }

            if (contentUri == null) {
                contentUri = dm.getUriForDownloadedFile(downloadId)
            }

            if (contentUri == null) {
                Toast.makeText(context, "Could not locate downloaded APK file", Toast.LENGTH_SHORT).show()
                return false
            }

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(installIntent)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "OTA install error: ${e.localizedMessage}", e)
            Toast.makeText(context, "Install failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            return false
        }
    }

    fun openDownloadsFolder() {
        try {
            val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path), "*/*")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e2: Exception) {}
        }
    }

    fun isNewerVersion(currentVersion: String, remoteVersion: String): Boolean {
        try {
            val cleanCurrent = currentVersion.trim().removePrefix("v").removePrefix("V")
            val cleanRemote = remoteVersion.trim().removePrefix("v").removePrefix("V")
            if (cleanCurrent == cleanRemote) return false

            fun parseParts(ver: String): List<Int> {
                val normalized = ver.replace(Regex("[^0-9.]"), ".")
                return normalized.split(".")
                    .filter { it.isNotBlank() }
                    .mapNotNull { it.toIntOrNull() }
            }

            val currentParts = parseParts(cleanCurrent)
            val remoteParts = parseParts(cleanRemote)
            val maxLength = maxOf(currentParts.size, remoteParts.size)

            for (i in 0 until maxLength) {
                val currentPart = if (i < currentParts.size) currentParts[i] else 0
                val remotePart = if (i < remoteParts.size) remoteParts[i] else 0
                if (remotePart > currentPart) return true
                else if (remotePart < currentPart) return false
            }

            return cleanRemote > cleanCurrent
        } catch (e: Exception) {
            return remoteVersion.removePrefix("v") > currentVersion.removePrefix("v")
        }
    }

    suspend fun trackDownloadProgress(
        downloadId: Long,
        info: UpdateInfo,
        onState: (AppUpdateState) -> Unit
    ) {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        var downloading = true
        while (downloading) {
            try {
                val cursor = dm.query(DownloadManager.Query().setFilterById(downloadId))
                if (cursor != null && cursor.moveToFirst()) {
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    val effectiveTotal = if (total > 0) total else info.fileSizeBytes

                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            onState(
                                AppUpdateState.ReadyToInstall(
                                    downloadId = downloadId,
                                    fileName = info.fileName,
                                    version = info.version,
                                    fileSizeFormatted = info.fileSizeFormatted
                                )
                            )
                            downloading = false
                        }
                        DownloadManager.STATUS_FAILED -> {
                            val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                            Log.w(TAG, "Download failed with reason code: $reason")
                            onState(AppUpdateState.Error("Download failed (Error $reason). Please check your internet connection."))
                            downloading = false
                        }
                        else -> {
                            val progress = if (effectiveTotal > 0) {
                                ((downloaded * 100L) / effectiveTotal).toInt().coerceIn(0, 100)
                            } else {
                                0
                            }
                            onState(
                                AppUpdateState.Downloading(
                                    progress = progress,
                                    bytesDownloaded = downloaded,
                                    totalBytes = effectiveTotal,
                                    downloadId = downloadId,
                                    fileName = info.fileName,
                                    version = info.version
                                )
                            )
                        }
                    }
                    cursor.close()
                } else {
                    downloading = false
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error querying download progress: ${e.message}")
            }
            delay(350)
        }
    }

    data class UpdateInfo(
        val currentVersion: String = "",
        val version: String,
        val releaseTitle: String = "",
        val publishedDate: String = "",
        val fileSizeFormatted: String = "",
        val fileSizeBytes: Long = 0L,
        val downloadUrl: String,
        val fileName: String = "",
        val changelogText: String = "",
        val versionChangelogs: List<ReleaseChangelog> = emptyList()
    ) {
        val changelog: String get() = changelogText
    }
}

sealed class AppUpdateState {
    object Idle : AppUpdateState()

    data class Checking(val isManual: Boolean = false) : AppUpdateState()

    data class Available(val info: UpdateChecker.UpdateInfo) : AppUpdateState()

    data class UpToDate(
        val currentVersion: String,
        val latestReleaseTitle: String = "",
        val latestReleaseDate: String = "",
        val recentHighlights: List<String> = emptyList()
    ) : AppUpdateState()

    data class Downloading(
        val progress: Int,
        val bytesDownloaded: Long = 0L,
        val totalBytes: Long = 0L,
        val downloadId: Long = 0L,
        val fileName: String = "",
        val version: String = ""
    ) : AppUpdateState()

    data class ReadyToInstall(
        val downloadId: Long,
        val fileName: String = "",
        val version: String = "",
        val fileSizeFormatted: String = ""
    ) : AppUpdateState()

    data class Error(
        val message: String,
        val isManual: Boolean = false
    ) : AppUpdateState()
}

typealias OtaState = AppUpdateState
