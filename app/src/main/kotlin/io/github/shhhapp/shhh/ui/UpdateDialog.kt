package io.github.shhhapp.shhh.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.shhhapp.shhh.R
import io.github.shhhapp.shhh.update.ReleaseInfo
import io.github.shhhapp.shhh.update.UpdateChecker
import io.github.shhhapp.shhh.update.UpdateInstaller
import kotlinx.coroutines.launch

private enum class Phase { IDLE, NEEDS_PERMISSION, DOWNLOADING, READY, FAILED }

/**
 * Walks the user from "an update exists" through download to the system
 * installer. The installer's own confirmation UI still applies.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UpdateDialog(
    release: ReleaseInfo,
    checker: UpdateChecker,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf(Phase.IDLE) }
    var progress by remember { mutableStateOf<Int?>(null) }

    fun startDownload() {
        phase = Phase.DOWNLOADING
        progress = null
        scope.launch {
            val apk = UpdateInstaller.updateApkFile(context)
            if (checker.download(release, apk) { progress = it }) {
                phase = Phase.READY
                UpdateInstaller.install(context, apk)
            } else {
                phase = Phase.FAILED
            }
        }
    }

    // Shared by IDLE, FAILED and NEEDS_PERMISSION: download once the
    // "Install unknown apps" toggle is granted, otherwise surface it.
    fun downloadOrAskPermission() {
        if (UpdateInstaller.canInstall(context)) {
            startDownload()
        } else if (phase == Phase.NEEDS_PERMISSION) {
            UpdateInstaller.requestInstallPermission(context)
        } else {
            phase = Phase.NEEDS_PERMISSION
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_dialog_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.update_dialog_version, release.versionName),
                    style = MaterialTheme.typography.bodyMedium
                )
                if (release.notes.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = release.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .heightIn(max = 180.dp)
                            .verticalScroll(rememberScrollState())
                    )
                }
                // if-chain, not an exhaustive when: a when-expression would
                // compile in a dead NoWhenBranchMatched throw the coverage
                // gate counts as a missed line.
                if (phase == Phase.NEEDS_PERMISSION) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.update_permission_body),
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else if (phase == Phase.DOWNLOADING) {
                    Spacer(Modifier.height(12.dp))
                    val percent = progress
                    Text(
                        text = if (percent != null) {
                            stringResource(R.string.update_dialog_downloading, percent)
                        } else {
                            stringResource(R.string.update_dialog_downloading_unknown)
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    // Material 3 Expressive: the wavy indicator carries its
                    // own motion, so the download visibly lives even between
                    // progress callbacks.
                    if (percent != null) {
                        LinearWavyProgressIndicator(
                            progress = { percent / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                    }
                } else if (phase == Phase.FAILED) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.update_dialog_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            // While DOWNLOADING there is no confirm action: dismissing cancels.
            if (phase == Phase.IDLE) {
                TextButton(onClick = ::downloadOrAskPermission) {
                    Text(stringResource(R.string.update_dialog_download))
                }
            } else if (phase == Phase.NEEDS_PERMISSION) {
                TextButton(onClick = ::downloadOrAskPermission) {
                    Text(stringResource(R.string.update_permission_grant))
                }
            } else if (phase == Phase.FAILED) {
                TextButton(onClick = ::downloadOrAskPermission) {
                    Text(stringResource(R.string.update_dialog_retry))
                }
            } else if (phase == Phase.READY) {
                TextButton(
                    onClick = {
                        UpdateInstaller.install(context, UpdateInstaller.updateApkFile(context))
                    }
                ) {
                    Text(stringResource(R.string.update_dialog_install))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.update_dialog_later))
            }
        }
    )
}
