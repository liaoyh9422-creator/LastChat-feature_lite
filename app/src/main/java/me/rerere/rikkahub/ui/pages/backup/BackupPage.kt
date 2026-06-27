package me.rerere.rikkahub.ui.pages.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.ToastType
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.utils.fileSizeToString
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import java.io.File

import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
fun BackupPage(vm: BackupVM = koinViewModel()) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.backup_page_title))
                },
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    IconButton(onClick = {}) {
                        Icon(
                            imageVector = Icons.Rounded.History,
                            contentDescription = stringResource(R.string.backup_page_backup_logs),
                            tint = Color.Transparent,
                        )
                    }
                }
            )
        },
    ) { paddingValues ->
        ImportExportPage(
            vm = vm,
            modifier = Modifier
                .fillMaxSize(),
            contentPadding = paddingValues,
        )
    }
}

@Composable
private fun ImportExportPage(
    vm: BackupVM,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var isExporting by remember { mutableStateOf(false) }
    var isRestoring by remember { mutableStateOf(false) }
    var showRestartDialog by remember { mutableStateOf(false) }
    var restoreResult by remember { mutableStateOf<me.rerere.rikkahub.data.sync.LocalBackupSync.RestoreResult?>(null) }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri?.let { targetUri ->
            scope.launch {
                isExporting = true
                runCatching {
                    val exportFile = vm.exportToFile()
                    context.contentResolver.openOutputStream(targetUri)?.use { outputStream ->
                        FileInputStream(exportFile).use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    exportFile.delete()
                    toaster.show(
                        context.getString(R.string.backup_page_backup_success),
                        type = ToastType.Success
                    )
                }.onFailure { e ->
                    e.printStackTrace()
                    toaster.show(
                        context.getString(R.string.backup_page_restore_failed, e.message ?: ""),
                        type = ToastType.Error
                    )
                }
                isExporting = false
            }
        }
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { sourceUri ->
            scope.launch {
                isRestoring = true
                runCatching {
                    val tempFile = File(context.cacheDir, "temp_restore_${System.currentTimeMillis()}.zip")
                    context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                        FileOutputStream(tempFile).use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    val result = vm.restoreFromLocalFile(tempFile)
                    restoreResult = result
                    tempFile.delete()
                    toaster.show(
                        context.getString(R.string.backup_page_restore_success),
                        type = ToastType.Success
                    )
                    showRestartDialog = true
                }.onFailure { e ->
                    e.printStackTrace()
                    toaster.show(
                        context.getString(R.string.backup_page_restore_failed, e.message ?: ""),
                        type = ToastType.Error
                    )
                }
                isRestoring = false
            }
        }
    }

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = contentPadding,
    ) {
        item {
            Card(
                shape = me.rerere.rikkahub.ui.theme.AppShapes.CardLarge,
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = if (me.rerere.rikkahub.ui.theme.LocalDarkMode.current) androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow else androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                onClick = {
                    if (!isExporting) {
                        val timestamp = LocalDateTime.now()
                            .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"))
                        createDocumentLauncher.launch("LastChat_backup_$timestamp.zip")
                    }
                }
            ) {
                ListItem(
                    headlineContent = {
                        Text(stringResource(R.string.backup_page_local_backup_export))
                    },
                    supportingContent = {
                        Text(
                            if (isExporting) stringResource(R.string.backup_page_exporting) else stringResource(
                                R.string.backup_page_export_desc
                            )
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = {
                        if (isExporting) {
                            CircularWavyProgressIndicator(
                                modifier = Modifier.size(24.dp)
                            )
                        } else {
                            Icon(Icons.Rounded.FileUpload, null)
                        }
                    }
                )
            }
        }

        item {
            Card(
                shape = me.rerere.rikkahub.ui.theme.AppShapes.CardLarge,
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = if (me.rerere.rikkahub.ui.theme.LocalDarkMode.current) androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow else androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                onClick = {
                    if (!isRestoring) {
                        openDocumentLauncher.launch(arrayOf("application/zip"))
                    }
                }
            ) {
                ListItem(
                    headlineContent = {
                        Text(stringResource(R.string.backup_page_local_backup_import))
                    },
                    supportingContent = {
                        Text(
                            if (isRestoring) stringResource(R.string.backup_page_importing) else stringResource(
                                R.string.backup_page_import_desc
                            )
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = {
                        if (isRestoring) {
                            CircularWavyProgressIndicator(
                                modifier = Modifier.size(24.dp)
                            )
                        } else {
                            Icon(Icons.Rounded.SystemUpdateAlt, null)
                        }
                    }
                )
            }
        }
    }

    if (showRestartDialog) {
        BackupDialog(
            result = restoreResult,
            onConfirm = {
                vm.restartApp(context)
            }
        )
    }
}

@Composable
private fun BackupDialog(
    result: me.rerere.rikkahub.data.sync.LocalBackupSync.RestoreResult?,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.backup_page_restart_app)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.backup_page_restart_desc))
                result?.let {
                    if (it.sanitization.skippedRows > 0 || it.settingsCleanup.totalIssuesFixed > 0 || it.settingsCleanup.unsupportedZipEntriesBytes > 0) {
                        Card(
                            colors = androidx.compose.material3.CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            )
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text(
                                    text = "Restore Report:",
                                    style = MaterialTheme.typography.labelMedium
                                )
                                if (it.sanitization.skippedRows > 0) {
                                    Text("• Removed ${it.sanitization.skippedRows} corrupt/invalid items")
                                }
                                if (it.settingsCleanup.totalIssuesFixed > 0) {
                                    Text("• Fixed ${it.settingsCleanup.totalIssuesFixed} setting issues")
                                }
                                if (it.settingsCleanup.unsupportedZipEntriesBytes > 0) {
                                    Text("• Cleaned ${it.settingsCleanup.unsupportedZipEntriesBytes.fileSizeToString()} of junk data")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(R.string.backup_page_restart_app))
            }
        },
    )
}