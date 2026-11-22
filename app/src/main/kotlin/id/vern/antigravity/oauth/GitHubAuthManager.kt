package id.vern.antigravity.oauth

import android.content.Context
import android.os.Environment
import android.system.Os
import android.util.Log
import id.vern.antigravity.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class GitHubUser(
    val username: String,
    val name: String? = null,
    val email: String? = null,
    val avatarUrl: String? = null,
    val token: String
)

data class DeviceCodeInfo(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresIn: Int,
    val interval: Int
)

object GitHubAuthManager {

    private const val TAG = "GitHubAuthManager"

    // Official GitHub CLI OAuth App Client ID
    const val CLIENT_ID = "178c6fc778ccc68e1d6a"
    const val SCOPES = "repo,read:org,workflow,gist,user"

    private const val DEVICE_CODE_URL = "https://github.com/login/device/code"
    private const val OAUTH_TOKEN_URL = "https://github.com/login/oauth/access_token"
    private const val USER_API_URL = "https://api.github.com/user"

    private const val PREFS_NAME = "antigravity_github_prefs"
    private const val KEY_USERNAME = "gh_username"
    private const val KEY_NAME = "gh_name"
    private const val KEY_EMAIL = "gh_email"
    private const val KEY_AVATAR = "gh_avatar"
    private const val KEY_TOKEN = "gh_token"

    @Volatile
    private var cachedUser: GitHubUser? = null

    fun getStoredUser(context: Context): GitHubUser? {
        cachedUser?.let { return it }

        val filesDir = context.filesDir
        val homeDir = File(filesDir, "home")
        val internalHosts = File(homeDir, ".config/gh/hosts.yml")

        val tokenFromInternal = readTokenFromHostsFile(internalHosts)
        val userFromInternal = readUsernameFromHostsFile(internalHosts)

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val prefToken = prefs.getString(KEY_TOKEN, null)
        val prefUsername = prefs.getString(KEY_USERNAME, null)
        val prefName = prefs.getString(KEY_NAME, null)
        val prefEmail = prefs.getString(KEY_EMAIL, null)
        val prefAvatar = prefs.getString(KEY_AVATAR, null)

        val effectiveToken = tokenFromInternal ?: prefToken
        val effectiveUser = userFromInternal ?: prefUsername

        if (!effectiveToken.isNullOrBlank() && !effectiveUser.isNullOrBlank()) {
            val user = GitHubUser(
                username = effectiveUser,
                name = prefName,
                email = prefEmail,
                avatarUrl = prefAvatar,
                token = effectiveToken
            )
            cachedUser = user
            return user
        }

        // Check external pre-staged candidate locations
        val candidates = mutableListOf<File>()
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (downloadDir != null) {
            candidates.add(File(downloadDir, "Andronix/.config/gh/hosts.yml"))
            candidates.add(File(downloadDir, "Andronix/hosts.yml"))
            candidates.add(File(downloadDir, ".config/gh/hosts.yml"))
            candidates.add(File(downloadDir, "hosts.yml"))
        }
        candidates.add(File("/storage/emulated/0/Download/Andronix/.config/gh/hosts.yml"))
        candidates.add(File("/storage/emulated/0/Download/Andronix/hosts.yml"))
        candidates.add(File("/storage/emulated/0/Download/hosts.yml"))
        candidates.add(File("/sdcard/download/hosts.yml"))

        for (candidate in candidates) {
            if (candidate.isFile && candidate.canRead() && candidate.length() > 0) {
                val token = readTokenFromHostsFile(candidate)
                val username = readUsernameFromHostsFile(candidate) ?: "github_user"
                if (!token.isNullOrBlank()) {
                    val user = GitHubUser(
                        username = username,
                        token = token
                    )
                    saveCredentials(context, user)
                    cachedUser = user
                    return user
                }
            }
        }

        return null
    }

    suspend fun verifyAndFetchUser(token: String): Result<GitHubUser> = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val url = URL(USER_API_URL)
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 20000
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("User-Agent", "Antigravity-Android/2.0")
                setRequestProperty("Accept", "application/vnd.github.v3+json")
            }

            val code = conn.responseCode
            if (code in 200..299) {
                val response = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                val json = JSONObject(response)
                val username = json.getString("login")
                val name = json.optString("name", null)
                val email = json.optString("email", null)
                val avatarUrl = json.optString("avatar_url", null)

                val user = GitHubUser(
                    username = username,
                    name = name,
                    email = email,
                    avatarUrl = avatarUrl,
                    token = token
                )
                Result.success(user)
            } else {
                val errBody = conn.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
                Result.failure(Exception("GitHub API responded with code $code: $errBody"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "verifyAndFetchUser failed: ${e.message}", e)
            Result.failure(e)
        } finally {
            conn?.disconnect()
        }
    }

    suspend fun requestDeviceCode(): Result<DeviceCodeInfo> = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val url = URL(DEVICE_CODE_URL)
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 20000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("User-Agent", "Antigravity-Android/2.0")
            }

            val postData = "client_id=$CLIENT_ID&scope=${URLEncoder.encode(SCOPES, "UTF-8")}"
            OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use {
                it.write(postData)
                it.flush()
            }

            val code = conn.responseCode
            if (code in 200..299) {
                val response = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                val json = JSONObject(response)
                val deviceCode = json.getString("device_code")
                val userCode = json.getString("user_code")
                val verificationUri = json.optString("verification_uri", "https://github.com/login/device")
                val expiresIn = json.optInt("expires_in", 900)
                val interval = json.optInt("interval", 5)

                Result.success(
                    DeviceCodeInfo(
                        deviceCode = deviceCode,
                        userCode = userCode,
                        verificationUri = verificationUri,
                        expiresIn = expiresIn,
                        interval = interval
                    )
                )
            } else {
                val errBody = conn.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
                Result.failure(Exception("Failed requesting device code ($code): $errBody"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "requestDeviceCode failed: ${e.message}", e)
            Result.failure(e)
        } finally {
            conn?.disconnect()
        }
    }

    suspend fun pollDeviceCode(
        deviceCode: String,
        initialIntervalSeconds: Int,
        maxDurationSeconds: Int = 900,
        onStatusUpdate: ((String) -> Unit)? = null
    ): Result<GitHubUser> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val maxDurationMs = maxDurationSeconds * 1000L
        var pollInterval = maxOf(initialIntervalSeconds, 5)

        while (System.currentTimeMillis() - startTime < maxDurationMs) {
            delay(pollInterval * 1000L)

            var conn: HttpURLConnection? = null
            try {
                val url = URL(OAUTH_TOKEN_URL)
                conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 20000
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    setRequestProperty("User-Agent", "Antigravity-Android/2.0")
                }

                val postData = "client_id=$CLIENT_ID&device_code=$deviceCode&grant_type=urn:ietf:params:oauth:grant-type:device_code"
                OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use {
                    it.write(postData)
                    it.flush()
                }

                val response = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                val json = JSONObject(response)

                if (json.has("access_token")) {
                    val token = json.getString("access_token")
                    onStatusUpdate?.invoke("Retrieving profile for authenticated account...")
                    val userResult = verifyAndFetchUser(token)
                    return@withContext if (userResult.isSuccess) {
                        Result.success(userResult.getOrThrow())
                    } else {
                        Result.success(GitHubUser(username = "github_user", token = token))
                    }
                }

                if (json.has("error")) {
                    val error = json.getString("error")
                    when (error) {
                        "authorization_pending" -> {
                            onStatusUpdate?.invoke("Waiting for code confirmation on GitHub...")
                        }
                        "slow_down" -> {
                            pollInterval += 5
                            onStatusUpdate?.invoke("Rate limited by GitHub, slowing check frequency...")
                        }
                        "expired_token" -> {
                            return@withContext Result.failure(Exception("Code expired. Please generate a new code."))
                        }
                        "access_denied" -> {
                            return@withContext Result.failure(Exception("Login was canceled or denied."))
                        }
                        else -> {
                            val desc = json.optString("error_description", error)
                            return@withContext Result.failure(Exception(desc))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Transient error while polling device code: ${e.message}")
            } finally {
                conn?.disconnect()
            }
        }

        Result.failure(Exception("Authentication timed out after ${maxDurationSeconds}s. Please try again."))
    }

    fun saveCredentials(context: Context, user: GitHubUser) {
        cachedUser = user

        // Save in SharedPreferences
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_USERNAME, user.username)
            .putString(KEY_NAME, user.name)
            .putString(KEY_EMAIL, user.email)
            .putString(KEY_AVATAR, user.avatarUrl)
            .putString(KEY_TOKEN, user.token)
            .apply()

        val hostsContent = """
            github.com:
                user: ${user.username}
                oauth_token: ${user.token}
                git_protocol: https
        """.trimIndent() + "\n"

        val gitConfigContent = """
            [user]
            	name = ${user.name ?: user.username}
            	email = ${user.email ?: "${user.username}@users.noreply.github.com"}
            [credential "https://github.com"]
            	helper = !gh auth git-credential
        """.trimIndent() + "\n"

        // Write internal config files
        try {
            val filesDir = context.filesDir
            val homeDir = File(filesDir, "home").apply { mkdirs() }
            val ghDir = File(homeDir, ".config/gh").apply { mkdirs() }
            val hostsFile = File(ghDir, "hosts.yml")
            hostsFile.writeText(hostsContent, StandardCharsets.UTF_8)
            hostsFile.setReadable(true, false)
            try { Os.chmod(hostsFile.absolutePath, 420) } catch (e: Exception) {}

            val gitConfigFile = File(homeDir, ".gitconfig")
            gitConfigFile.writeText(gitConfigContent, StandardCharsets.UTF_8)
            gitConfigFile.setReadable(true, false)
            try { Os.chmod(gitConfigFile.absolutePath, 420) } catch (e: Exception) {}
        } catch (e: Exception) {
            Log.w(TAG, "Failed writing internal GitHub config: ${e.message}")
        }

        // Mirror to shared external storage per user rule
        try {
            val targets = listOf(
                File("/storage/emulated/0/Download/Andronix/.config/gh/hosts.yml"),
                File("/storage/emulated/0/Download/Andronix/hosts.yml"),
                File("/home/altafyafai/Android/Download/.config/gh/hosts.yml"),
                File("/home/altafyafai/Android/Download/hosts.yml"),
                File("/home/altafyafai/Download/hosts.yml"),
                File("/sdcard/download/hosts.yml")
            )
            for (target in targets) {
                target.parentFile?.mkdirs()
                target.writeText(hostsContent, StandardCharsets.UTF_8)
                target.setReadable(true, false)
            }

            val gitTargets = listOf(
                File("/storage/emulated/0/Download/Andronix/.gitconfig"),
                File("/home/altafyafai/Android/Download/.gitconfig"),
                File("/home/altafyafai/Download/.gitconfig"),
                File("/sdcard/download/.gitconfig")
            )
            for (target in gitTargets) {
                target.parentFile?.mkdirs()
                target.writeText(gitConfigContent, StandardCharsets.UTF_8)
                target.setReadable(true, false)
            }
            AppLogger.d("GitHubAuth", "Mirrored GitHub credentials and gitconfig to external storage")
        } catch (e: Exception) {
            Log.w(TAG, "External mirroring of GitHub credentials skipped: ${e.message}")
        }
    }

    fun logout(context: Context) {
        cachedUser = null

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()

        try {
            val filesDir = context.filesDir
            val internalHosts = File(filesDir, "home/.config/gh/hosts.yml")
            if (internalHosts.exists()) {
                internalHosts.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed deleting internal hosts.yml: ${e.message}")
        }
    }

    private fun readTokenFromHostsFile(file: File): String? {
        if (!file.isFile || !file.canRead() || file.length() == 0L) return null
        return try {
            val content = file.readText(StandardCharsets.UTF_8)
            val match = Regex("""oauth_token:\s*([^\s]+)""").find(content)
            match?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    private fun readUsernameFromHostsFile(file: File): String? {
        if (!file.isFile || !file.canRead() || file.length() == 0L) return null
        return try {
            val content = file.readText(StandardCharsets.UTF_8)
            val match = Regex("""user:\s*([^\s]+)""").find(content)
            match?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }
}
