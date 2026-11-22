package id.vern.antigravity.viewmodel

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import id.vern.antigravity.R
import id.vern.antigravity.install.CoreUpdateInfo
import id.vern.antigravity.install.CoreUpdateManager
import id.vern.antigravity.ui.components.TerminalLine
import id.vern.antigravity.util.AppUpdateState
import id.vern.antigravity.util.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object CoreUpdateDelegate {

    suspend fun checkForOtaUpdates(context: Context, isManualCheck: Boolean): AppUpdateState? {
        return try {
            val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION") context.packageManager.getPackageInfo(context.packageName, 0)
            }
            val currentVersion = pInfo.versionName ?: "1.0.0"
            val checker = UpdateChecker(context)
            checker.checkForUpdates(currentVersion, isManualCheck = isManualCheck)
        } catch (e: Exception) {
            Log.w("CoreUpdateDelegate", "Failed checking OTA updates: ${e.message}")
            if (isManualCheck) {
                AppUpdateState.Error("Failed to check for updates: ${e.localizedMessage ?: e.message}")
            } else {
                null
            }
        }
    }

    suspend fun startOtaDownload(
        context: Context,
        info: UpdateChecker.UpdateInfo,
        onProgress: (AppUpdateState) -> Unit
    ) {
        val checker = UpdateChecker(context)
        val downloadId = checker.startDownload(info)
        checker.trackDownloadProgress(downloadId, info) { state ->
            onProgress(state)
        }
    }

    fun cancelOtaDownload(context: Context, downloadId: Long) {
        val checker = UpdateChecker(context)
        checker.cancelDownload(downloadId)
    }

    fun snoozeOtaUpdate(context: Context) {
        val checker = UpdateChecker(context)
        checker.snoozeUpdate()
    }

    suspend fun checkForCoreEngineUpdate(
        context: Context,
        force: Boolean,
        currentState: CoreEngineUpdateState
    ): CoreEngineUpdateState? {
        val now = System.currentTimeMillis()
        val lastChecked = currentState.lastCheckedTime
        if (!force && lastChecked > 0 && now - lastChecked < 15 * 60 * 1000L) {
            return null
        }
        val current = CoreUpdateManager.getCurrentVersion(context)
        return try {
            val updateInfo = CoreUpdateManager.checkForUpdate(context)
            CoreEngineUpdateState(
                isChecking = false,
                currentVersion = current,
                latestVersion = updateInfo.version,
                isUpdateAvailable = updateInfo.isUpdateAvailable,
                downloadUrl = updateInfo.url,
                lastCheckedTime = System.currentTimeMillis(),
                error = null
            )
        } catch (e: Exception) {
            Log.w("CoreUpdateDelegate", "Failed checking core engine update: ${e.message}")
            currentState.copy(
                isChecking = false,
                currentVersion = current,
                error = e.message ?: "Network error"
            )
        }
    }

    suspend fun applyCoreEngineUpdate(
        context: Context,
        state: CoreEngineUpdateState,
        onOutputLine: (TerminalLine) -> Unit,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val latestVer = state.latestVersion ?: return
        val url = state.downloadUrl ?: return
        val updateInfo = CoreUpdateInfo(
            version = latestVer,
            url = url,
            currentVersion = state.currentVersion,
            isUpdateAvailable = true
        )

        val result = CoreUpdateManager.downloadAndApply(context, updateInfo) { progress, step ->
            onOutputLine(TerminalLine(text = step, isError = false))
        }

        if (result.isSuccess) {
            val newVer = result.getOrNull() ?: latestVer
            onOutputLine(TerminalLine(text = context.getString(R.string.core_update_success, newVer), isError = false))
            onSuccess(newVer)
        } else {
            val err = result.exceptionOrNull()?.message ?: "Unknown error"
            onOutputLine(TerminalLine(text = context.getString(R.string.core_update_failed, err), isError = true))
            onError(err)
        }
    }
}
