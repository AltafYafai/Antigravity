package id.vern.antigravity.oauth

import android.content.Context
import id.vern.antigravity.util.AppLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class StoredGitHubAccount(
    val username: String,
    val name: String? = null,
    val email: String? = null,
    val avatarUrl: String? = null,
    val token: String,
    val lastUsedAt: Long = System.currentTimeMillis()
) {
    fun toGitHubUser(): GitHubUser = GitHubUser(
        username = username,
        name = name,
        email = email,
        avatarUrl = avatarUrl,
        token = token
    )
}

object GitHubAccountManager {
    private const val TAG = "GitHubAccountManager"
    private const val ACCOUNTS_FILE_NAME = "github_accounts_store.json"
    private val lock = Any()

    private fun getStoreFile(context: Context): File {
        val dir = File(context.filesDir, ".gemini")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, ACCOUNTS_FILE_NAME)
    }

    fun getAccounts(context: Context): List<StoredGitHubAccount> = synchronized(lock) {
        val file = getStoreFile(context)
        if (!file.isFile || file.length() == 0L) {
            importExistingAccountIfEmpty(context)
            val updatedFile = getStoreFile(context)
            if (!updatedFile.isFile || updatedFile.length() == 0L) return emptyList()
        }
        return try {
            val content = file.readText(Charsets.UTF_8)
            val root = JSONObject(content)
            val arr = root.optJSONArray("accounts") ?: return emptyList()
            val list = mutableListOf<StoredGitHubAccount>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val username = obj.optString("username").trim()
                val name = obj.optString("name").takeIf { it.isNotBlank() }
                val email = obj.optString("email").takeIf { it.isNotBlank() }
                val avatarUrl = obj.optString("avatarUrl").takeIf { it.isNotBlank() }
                val token = obj.optString("token").trim()
                val lastUsedAt = obj.optLong("lastUsedAt", System.currentTimeMillis())

                if (username.isNotBlank() && token.isNotBlank()) {
                    list.add(
                        StoredGitHubAccount(
                            username = username,
                            name = name,
                            email = email,
                            avatarUrl = avatarUrl,
                            token = token,
                            lastUsedAt = lastUsedAt
                        )
                    )
                }
            }
            list.sortedByDescending { it.lastUsedAt }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error reading GitHub accounts: ${e.message}")
            emptyList()
        }
    }

    fun getActiveUsername(context: Context): String? = synchronized(lock) {
        val file = getStoreFile(context)
        if (!file.isFile || file.length() == 0L) return null
        return try {
            val content = file.readText(Charsets.UTF_8)
            val root = JSONObject(content)
            root.optString("activeUsername").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    fun getActiveAccount(context: Context): StoredGitHubAccount? {
        val accounts = getAccounts(context)
        if (accounts.isEmpty()) return null
        val activeUsername = getActiveUsername(context)
        return accounts.firstOrNull { it.username.equals(activeUsername, ignoreCase = true) }
            ?: accounts.firstOrNull()
    }

    private fun persist(context: Context, activeUsername: String?, accounts: List<StoredGitHubAccount>) {
        val file = getStoreFile(context)
        try {
            val root = JSONObject()
            root.put("activeUsername", activeUsername ?: "")
            val arr = JSONArray()
            for (acc in accounts) {
                val obj = JSONObject().apply {
                    put("username", acc.username)
                    put("name", acc.name ?: "")
                    put("email", acc.email ?: "")
                    put("avatarUrl", acc.avatarUrl ?: "")
                    put("token", acc.token)
                    put("lastUsedAt", acc.lastUsedAt)
                }
                arr.put(obj)
            }
            root.put("accounts", arr)
            file.parentFile?.mkdirs()
            file.writeText(root.toString(2), Charsets.UTF_8)
            file.setReadable(true, false)
            file.setWritable(true, false)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to persist GitHub accounts: ${e.message}")
        }
    }

    fun saveOrUpdateAccount(
        context: Context,
        user: GitHubUser,
        makeActive: Boolean = true
    ): StoredGitHubAccount = synchronized(lock) {
        val current = getAccounts(context).toMutableList()
        val index = current.indexOfFirst { it.username.equals(user.username, ignoreCase = true) }
        val now = System.currentTimeMillis()

        val finalName = user.name?.takeIf { it.isNotBlank() }
            ?: if (index >= 0) current[index].name else null
        val finalEmail = user.email?.takeIf { it.isNotBlank() }
            ?: if (index >= 0) current[index].email else null
        val finalAvatar = user.avatarUrl?.takeIf { it.isNotBlank() }
            ?: if (index >= 0) current[index].avatarUrl else null

        val stored = StoredGitHubAccount(
            username = user.username,
            name = finalName,
            email = finalEmail,
            avatarUrl = finalAvatar,
            token = user.token,
            lastUsedAt = now
        )

        if (index >= 0) {
            current[index] = stored
        } else {
            current.add(0, stored)
        }

        val activeUsername = if (makeActive) user.username else (getActiveUsername(context) ?: user.username)
        persist(context, activeUsername, current)

        if (makeActive) {
            GitHubAuthManager.applyCredentials(context, stored.toGitHubUser())
        }

        AppLogger.d(TAG, "Saved GitHub account @${user.username} (active=$makeActive)")
        stored
    }

    fun switchAccount(context: Context, username: String): Boolean = synchronized(lock) {
        val accounts = getAccounts(context)
        val target = accounts.firstOrNull { it.username.equals(username, ignoreCase = true) }
            ?: return false

        val updatedList = accounts.map {
            if (it.username.equals(target.username, ignoreCase = true)) {
                it.copy(lastUsedAt = System.currentTimeMillis())
            } else {
                it
            }
        }
        persist(context, target.username, updatedList)
        GitHubAuthManager.applyCredentials(context, target.toGitHubUser())
        AppLogger.d(TAG, "Switched active GitHub account to @${target.username}")
        true
    }

    fun removeAccount(context: Context, username: String): Boolean = synchronized(lock) {
        val accounts = getAccounts(context).toMutableList()
        val index = accounts.indexOfFirst { it.username.equals(username, ignoreCase = true) }
        if (index < 0) return false

        val removed = accounts.removeAt(index)
        val currentActive = getActiveUsername(context)
        val newActiveUsername = if (currentActive.equals(removed.username, ignoreCase = true)) {
            accounts.firstOrNull()?.username
        } else {
            currentActive
        }

        persist(context, newActiveUsername, accounts)

        if (newActiveUsername != null) {
            val next = accounts.first { it.username.equals(newActiveUsername, ignoreCase = true) }
            GitHubAuthManager.applyCredentials(context, next.toGitHubUser())
            AppLogger.d(TAG, "Removed @${removed.username}, switched to next account @${next.username}")
        } else {
            GitHubAuthManager.logout(context)
            AppLogger.d(TAG, "Removed @${removed.username}, no remaining GitHub accounts")
        }
        true
    }

    fun importExistingAccountIfEmpty(context: Context) {
        val file = getStoreFile(context)
        if (file.isFile && file.length() > 0L) return

        val stored = GitHubAuthManager.getDirectStoredUser(context) ?: return
        val current = mutableListOf(
            StoredGitHubAccount(
                username = stored.username,
                name = stored.name,
                email = stored.email,
                avatarUrl = stored.avatarUrl,
                token = stored.token,
                lastUsedAt = System.currentTimeMillis()
            )
        )
        persist(context, stored.username, current)
        AppLogger.d(TAG, "Imported existing GitHub credentials as initial account: @${stored.username}")
    }

    fun clearAll(context: Context) {
        synchronized(lock) {
            val file = getStoreFile(context)
            if (file.exists()) {
                file.delete()
            }
            GitHubAuthManager.logout(context)
            AppLogger.d(TAG, "Cleared all GitHub accounts from store")
        }
    }
}
