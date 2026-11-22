package id.vern.antigravity.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import id.vern.antigravity.R
import id.vern.antigravity.ui.theme.AntigravityAmber
import id.vern.antigravity.ui.theme.AntigravityBlue
import id.vern.antigravity.ui.theme.AntigravityGreen
import id.vern.antigravity.ui.theme.AntigravityPurple
import id.vern.antigravity.util.AppUpdateState
import id.vern.antigravity.util.ChangelogItem
import id.vern.antigravity.util.ChangelogType
import id.vern.antigravity.util.UpdateChecker
import java.util.Locale

@Composable
fun AppUpdateDialog(
    state: AppUpdateState,
    onDismiss: () -> Unit,
    onLater: () -> Unit = onDismiss,
    onCancelDownload: (Long) -> Unit = {},
    onStartDownload: (UpdateChecker.UpdateInfo) -> Unit,
    onInstallApk: (Long) -> Unit,
    onOpenDownloadsFolder: () -> Unit = {}
) {
    if (state is AppUpdateState.Idle || state is AppUpdateState.Checking) {
        return
    }

    val isDownloading = state is AppUpdateState.Downloading

    Dialog(
        onDismissRequest = {
            if (!isDownloading) onDismiss()
        },
        properties = DialogProperties(
            dismissOnBackPress = !isDownloading,
            dismissOnClickOutside = !isDownloading,
            usePlatformDefaultWidth = false
        )
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 500.dp)
                .padding(vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp)
            ) {
                when (state) {
                    is AppUpdateState.Available -> {
                        UpdateAvailableContent(
                            info = state.info,
                            onDismiss = onDismiss,
                            onLater = onLater,
                            onStartDownload = onStartDownload
                        )
                    }
                    is AppUpdateState.Downloading -> {
                        UpdateDownloadingContent(
                            state = state,
                            onCancelDownload = onCancelDownload,
                            onHideDownload = onDismiss
                        )
                    }
                    is AppUpdateState.ReadyToInstall -> {
                        UpdateReadyToInstallContent(
                            state = state,
                            onDismiss = onDismiss,
                            onInstallApk = onInstallApk,
                            onOpenDownloadsFolder = onOpenDownloadsFolder
                        )
                    }
                    is AppUpdateState.UpToDate -> {
                        UpdateUpToDateContent(
                            state = state,
                            onDismiss = onDismiss
                        )
                    }
                    is AppUpdateState.Error -> {
                        UpdateErrorContent(
                            message = state.message,
                            onDismiss = onDismiss
                        )
                    }
                    else -> {}
                }
            }
        }
    }
}

@Composable
private fun UpdateAvailableContent(
    info: UpdateChecker.UpdateInfo,
    onDismiss: () -> Unit,
    onLater: () -> Unit,
    onStartDownload: (UpdateChecker.UpdateInfo) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(AntigravityBlue.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.RocketLaunch,
                contentDescription = null,
                tint = AntigravityBlue,
                modifier = Modifier.size(26.dp)
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.ota_dialog_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.ota_dialog_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        IconButton(
            onClick = onDismiss,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.ota_btn_cancel),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                modifier = Modifier.size(18.dp)
            )
        }
    }

    Spacer(modifier = Modifier.height(14.dp))

    // Version Migration Badge
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp)
        ) {
            val currText = if (info.currentVersion.isNotBlank()) "v${info.currentVersion.removePrefix("v")}" else "Current"
            val targetText = "v${info.version.removePrefix("v")}"

            Text(
                text = currText,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.width(8.dp))

            Icon(
                imageVector = Icons.Default.ArrowForward,
                contentDescription = null,
                tint = AntigravityBlue,
                modifier = Modifier.size(14.dp)
            )

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = targetText,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = AntigravityBlue
            )

            Spacer(modifier = Modifier.weight(1f))

            Surface(
                color = AntigravityGreen.copy(alpha = 0.16f),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    text = "NEW VERSION",
                    color = AntigravityGreen,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 9.sp,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(10.dp))

    // Meta chips row
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        MetaChip(text = info.fileSizeFormatted.ifBlank { "APK Package" })
        if (info.publishedDate.isNotBlank()) {
            MetaChip(text = info.publishedDate)
        }
        MetaChip(text = "Official Release")
    }

    Spacer(modifier = Modifier.height(14.dp))

    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
    )

    Spacer(modifier = Modifier.height(12.dp))

    // Changelog Section Header
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            imageVector = Icons.Default.AutoAwesome,
            contentDescription = null,
            tint = AntigravityAmber,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.ota_changelog_section_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }

    Spacer(modifier = Modifier.height(8.dp))

    // Changelog Container
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 110.dp, max = 240.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(12.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (info.versionChangelogs.isNotEmpty()) {
                info.versionChangelogs.forEachIndexed { vIdx, rel ->
                    if (info.versionChangelogs.size > 1) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = AntigravityBlue.copy(alpha = 0.12f),
                            modifier = Modifier.padding(bottom = 4.dp)
                        ) {
                            Text(
                                text = "Build ${rel.version.removePrefix("v")}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = AntigravityBlue,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }

                    if (rel.items.isNotEmpty()) {
                        rel.items.forEach { item ->
                            ChangelogItemRow(item = item)
                        }
                    } else if (rel.rawBody.isNotBlank()) {
                        Text(
                            text = rel.rawBody.trim(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    if (vIdx < info.versionChangelogs.size - 1) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }
            } else if (info.changelogText.isNotBlank()) {
                Text(
                    text = info.changelogText.trim(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            } else {
                Text(
                    text = "Performance enhancements and stability fixes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(20.dp))

    // Proper Action Buttons: Cancel, Later, Update Now
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        TextButton(
            onClick = onDismiss,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = stringResource(R.string.ota_btn_cancel),
                style = MaterialTheme.typography.labelLarge
            )
        }

        OutlinedButton(
            onClick = onLater,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = stringResource(R.string.ota_btn_later),
                style = MaterialTheme.typography.labelLarge
            )
        }

        Button(
            onClick = { onStartDownload(info) },
            colors = ButtonDefaults.buttonColors(containerColor = AntigravityBlue),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.weight(1.35f)
        ) {
            Icon(
                imageVector = Icons.Default.Download,
                contentDescription = null,
                modifier = Modifier.size(17.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.ota_btn_update_now),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
private fun UpdateDownloadingContent(
    state: AppUpdateState.Downloading,
    onCancelDownload: (Long) -> Unit,
    onHideDownload: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(AntigravityBlue.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Downloading,
                contentDescription = null,
                tint = AntigravityBlue,
                modifier = Modifier.size(26.dp)
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.ota_downloading_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = state.fileName.ifBlank { "Antigravity update package" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    Spacer(modifier = Modifier.height(20.dp))

    // Progress Bar
    LinearProgressIndicator(
        progress = { state.progress / 100f },
        color = AntigravityBlue,
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
    )

    Spacer(modifier = Modifier.height(10.dp))

    // Progress Stats
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = "${state.progress}%",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = AntigravityBlue
        )

        val downloadedMb = state.bytesDownloaded.toDouble() / (1024 * 1024)
        val totalMb = state.totalBytes.toDouble() / (1024 * 1024)
        val text = if (state.totalBytes > 0) {
            String.format(Locale.US, "%.1f MB / %.1f MB", downloadedMb, totalMb)
        } else {
            String.format(Locale.US, "%.1f MB downloaded", downloadedMb)
        }

        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    Spacer(modifier = Modifier.height(14.dp))

    // Background Hint Card
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = AntigravityBlue,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.ota_download_background_tip),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    Spacer(modifier = Modifier.height(20.dp))

    // Proper Buttons: Cancel Download & Run in Background
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedButton(
            onClick = { onCancelDownload(state.downloadId) },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.ota_btn_cancel_download),
                style = MaterialTheme.typography.labelMedium
            )
        }

        Button(
            onClick = onHideDownload,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = stringResource(R.string.ota_btn_hide_download),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun UpdateReadyToInstallContent(
    state: AppUpdateState.ReadyToInstall,
    onDismiss: () -> Unit,
    onInstallApk: (Long) -> Unit,
    onOpenDownloadsFolder: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(AntigravityGreen.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = AntigravityGreen,
                modifier = Modifier.size(28.dp)
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.ota_install_ready_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.ota_install_ready_subtitle, state.version.ifBlank { "Latest" }),
                style = MaterialTheme.typography.bodySmall,
                color = AntigravityGreen,
                fontWeight = FontWeight.Medium
            )
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    // Package Details Card
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = state.fileName.ifBlank { "Antigravity-latest.apk" },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (state.fileSizeFormatted.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Package size: ${state.fileSizeFormatted}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    Text(
        text = stringResource(R.string.ota_install_ready_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(modifier = Modifier.height(20.dp))

    // Action Buttons: Later, Open in Files, Install Now
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        TextButton(
            onClick = onDismiss,
            shape = RoundedCornerShape(10.dp)
        ) {
            Text(
                text = stringResource(R.string.ota_btn_later),
                style = MaterialTheme.typography.labelMedium
            )
        }

        OutlinedButton(
            onClick = onOpenDownloadsFolder,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.ota_btn_open_files),
                style = MaterialTheme.typography.labelMedium
            )
        }

        Button(
            onClick = { onInstallApk(state.downloadId) },
            colors = ButtonDefaults.buttonColors(containerColor = AntigravityGreen),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.weight(1.3f)
        ) {
            Icon(
                imageVector = Icons.Default.SystemUpdate,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(17.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.ota_btn_install_now),
                fontWeight = FontWeight.Bold,
                color = Color.White,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
private fun UpdateUpToDateContent(
    state: AppUpdateState.UpToDate,
    onDismiss: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(AntigravityGreen.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Verified,
                contentDescription = null,
                tint = AntigravityGreen,
                modifier = Modifier.size(32.dp)
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = stringResource(R.string.ota_up_to_date_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = stringResource(R.string.ota_up_to_date_subtitle, "v${state.currentVersion.removePrefix("v")}"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (state.recentHighlights.isNotEmpty()) {
            Spacer(modifier = Modifier.height(14.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Current Build Highlights",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = AntigravityGreen
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    state.recentHighlights.forEach { highlight ->
                        Row(modifier = Modifier.padding(vertical = 2.dp)) {
                            Text(
                                text = "• ",
                                style = MaterialTheme.typography.bodySmall,
                                color = AntigravityGreen,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = highlight,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = onDismiss,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(R.string.ota_btn_done),
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun UpdateErrorContent(
    message: String,
    onDismiss: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.error.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Update Check Failed",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }

    Spacer(modifier = Modifier.height(20.dp))

    Button(
        onClick = onDismiss,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Dismiss")
    }
}

@Composable
private fun ChangelogItemRow(item: ChangelogItem) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        CategoryBadge(category = item.category)

        Spacer(modifier = Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f)) {
            if (item.title.isNotBlank()) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            if (item.description.isNotBlank()) {
                Text(
                    text = item.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.title.isNotBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun CategoryBadge(category: ChangelogType) {
    val (bgColor, textColor, label) = when (category) {
        ChangelogType.FEATURE -> Triple(
            AntigravityGreen.copy(alpha = 0.16f),
            AntigravityGreen,
            stringResource(R.string.ota_tag_feature)
        )
        ChangelogType.FIX -> Triple(
            AntigravityAmber.copy(alpha = 0.16f),
            AntigravityAmber,
            stringResource(R.string.ota_tag_fix)
        )
        ChangelogType.IMPROVEMENT -> Triple(
            AntigravityBlue.copy(alpha = 0.16f),
            AntigravityBlue,
            stringResource(R.string.ota_tag_improvement)
        )
        ChangelogType.SECURITY -> Triple(
            AntigravityPurple.copy(alpha = 0.16f),
            AntigravityPurple,
            stringResource(R.string.ota_tag_security)
        )
        ChangelogType.GENERAL -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "•"
        )
    }

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = bgColor
    ) {
        Text(
            text = label,
            color = textColor,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun MetaChip(text: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
