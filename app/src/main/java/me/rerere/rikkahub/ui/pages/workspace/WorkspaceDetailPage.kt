package me.rerere.rikkahub.ui.pages.workspace

import android.content.Intent
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.tools.resolveWorkspaceToolApproval
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.nav.OneUITopAppBar
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstallStage
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun WorkspaceDetailPage(id: String) {
    val navController = LocalNavController.current
    val vm: WorkspaceDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val state by vm.state.collectAsStateWithLifecycle()
    val installProgress by vm.installProgress.collectAsStateWithLifecycle()
    val installError by vm.installError.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf(0) }
    var deleteTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    var showInstallDialog by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val fileName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) cursor.getString(nameIndex) else null
            } else null
        } ?: uri.lastPathSegment ?: "imported_file"
        val inputStream = context.contentResolver.openInputStream(uri) ?: return@rememberLauncherForActivityResult
        vm.importFile(inputStream, fileName)
    }
    var exportTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val entry = exportTarget.also { exportTarget = null } ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        val outputStream = context.contentResolver.openOutputStream(uri) ?: return@rememberLauncherForActivityResult
        vm.exportFile(entry, outputStream)
    }

    BackHandler(enabled = page == 1 && state.path.isNotBlank()) {
        vm.goUp()
    }

    Scaffold(
        topBar = {
            OneUITopAppBar(
                title = state.workspace?.name ?: stringResource(R.string.workspace_detail_title),
                scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(),
                navigationIcon = { BackButton() },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = page == 0,
                    label = { Text(stringResource(R.string.workspace_detail_tab_basic)) },
                    icon = { Text("1") },
                    onClick = { page = 0 },
                )
                NavigationBarItem(
                    selected = page == 1,
                    label = { Text(stringResource(R.string.workspace_detail_tab_files)) },
                    icon = { Text("2") },
                    onClick = { page = 1 },
                )
            }
        },
    ) { innerPadding ->
        when (page) {
            0 -> WorkspaceBasicPage(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                workspace = state.workspace,
                installProgress = installProgress,
                onInstallRootfs = { showInstallDialog = true },
                onToolApprovalChange = vm::setToolApproval,
                onOpenTerminal = { navController.navigate(Screen.WorkspaceTerminal(id)) },
            )
            else -> WorkspaceFilesPage(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                state = state,
                onSelectArea = vm::selectArea,
                onGoUp = vm::goUp,
                onOpen = vm::open,
                onDelete = { deleteTarget = it },
                onImport = { filePicker.launch(arrayOf("*/*")) },
                onExport = { entry ->
                    exportTarget = entry
                    exportLauncher.launch(entry.name)
                },
                onShare = { entry ->
                    vm.shareFile(entry, context.cacheDir) { file ->
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            file,
                        )
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/octet-stream"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, null))
                    }
                },
            )
        }
    }

    if (showInstallDialog && state.workspace != null) {
        InstallRootfsDialog(
            workspace = state.workspace!!,
            onDismiss = { showInstallDialog = false },
            onConfirm = { url ->
                vm.installRootfs(url)
                showInstallDialog = false
            },
        )
    }

    installError?.let { message ->
        AlertDialog(
            onDismissRequest = vm::dismissInstallError,
            title = { Text(stringResource(R.string.workspace_detail_rootfs_install_failed)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = vm::dismissInstallError) {
                    Text(stringResource(R.string.confirm))
                }
            },
        )
    }

    deleteTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(if (entry.isDirectory) stringResource(R.string.workspace_detail_delete_directory) else stringResource(R.string.workspace_detail_delete_file)) },
            text = { Text(stringResource(R.string.workspace_detail_will_delete, entry.path)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(entry)
                    deleteTarget = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun WorkspaceBasicPage(
    modifier: Modifier = Modifier,
    workspace: WorkspaceEntity?,
    installProgress: RootfsInstallProgress?,
    onInstallRootfs: () -> Unit,
    onToolApprovalChange: (String, Boolean) -> Unit,
    onOpenTerminal: () -> Unit,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.workspace_detail_workspace_info), style = MaterialTheme.typography.titleMedium)
                    WorkspaceInfoRow(stringResource(R.string.workspace_detail_name), workspace?.name ?: stringResource(R.string.workspace_detail_loading))
                    WorkspaceInfoRow(stringResource(R.string.workspace_detail_shell_status), workspace?.shellStatus ?: "-")
                    Button(onClick = onInstallRootfs, enabled = workspace != null) {
                        Text(
                            when {
                                installProgress != null -> stringResource(R.string.workspace_detail_installing)
                                workspace?.shellStatus == WorkspaceShellStatus.READY.name -> stringResource(R.string.workspace_detail_reinstall_rootfs)
                                else -> stringResource(R.string.workspace_detail_install_rootfs)
                            }
                        )
                    }
                    if (workspace?.shellStatus == WorkspaceShellStatus.READY.name) {
                        Button(onClick = onOpenTerminal) {
                            Text(stringResource(R.string.workspace_terminal_title))
                        }
                    }
                    installProgress?.let { RootfsProgress(it) }
                }
            }
        }
        item {
            WorkspaceToolApprovalCard(workspace = workspace, onToolApprovalChange = onToolApprovalChange)
        }
    }
}

@Composable
private fun WorkspaceToolApprovalCard(
    workspace: WorkspaceEntity?,
    onToolApprovalChange: (String, Boolean) -> Unit,
) {
    val overrides = workspace?.toolApprovalOverrides().orEmpty()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.workspace_detail_tool_approval), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.workspace_detail_tool_approval_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            workspaceToolApprovalItems().forEach { (toolName, label) ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                        Text(toolName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Switch(
                        checked = resolveWorkspaceToolApproval(toolName, overrides),
                        onCheckedChange = { onToolApprovalChange(toolName, it) },
                        enabled = workspace != null,
                    )
                }
            }
        }
    }
}

@Composable
private fun workspaceToolApprovalItems() = listOf(
    "workspace_read_file" to stringResource(R.string.workspace_detail_tool_read_file),
    "workspace_write_file" to stringResource(R.string.workspace_detail_tool_write_file),
    "workspace_edit_file" to stringResource(R.string.workspace_detail_tool_edit_file),
    "workspace_shell" to stringResource(R.string.workspace_detail_tool_shell),
)

@Composable
private fun WorkspaceInfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(0.35f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.weight(0.65f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RootfsProgress(progress: RootfsInstallProgress) {
    val text = when (progress.stage) {
        RootfsInstallStage.DOWNLOADING -> stringResource(R.string.workspace_detail_downloading, progress.bytesRead.toString(), progress.totalBytes?.toString().orEmpty())
        RootfsInstallStage.EXTRACTING -> stringResource(R.string.workspace_detail_extracting, progress.entriesExtracted, progress.currentEntry?.let { " · $it" }.orEmpty())
        RootfsInstallStage.INSTALLED -> stringResource(R.string.workspace_detail_install_complete)
    }
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun InstallRootfsDialog(
    workspace: WorkspaceEntity,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var url by rememberSaveable(workspace.id) { mutableStateOf("https://github.com/moeru-ai/airi/releases/download/v0.1.1/ubuntu24.04-rootfs-arm64.tar.gz") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workspace_detail_install_rootfs)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.workspace_detail_install_rootfs_desc, workspace.name), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.workspace_detail_download_url)) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(url) }, enabled = url.isNotBlank()) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun WorkspaceFilesPage(
    modifier: Modifier = Modifier,
    state: WorkspaceDetailState,
    onSelectArea: (WorkspaceStorageArea) -> Unit,
    onGoUp: () -> Unit,
    onOpen: (WorkspaceFileEntry) -> Unit,
    onDelete: (WorkspaceFileEntry) -> Unit,
    onImport: () -> Unit,
    onExport: (WorkspaceFileEntry) -> Unit,
    onShare: (WorkspaceFileEntry) -> Unit,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.workspace_detail_tab_files), style = MaterialTheme.typography.titleMedium)
                    Text(if (state.area == WorkspaceStorageArea.FILES) stringResource(R.string.workspace_detail_area_files) else stringResource(R.string.workspace_detail_area_rootfs))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onSelectArea(WorkspaceStorageArea.FILES) }) { Text(stringResource(R.string.workspace_detail_area_files)) }
                        Button(onClick = { onSelectArea(WorkspaceStorageArea.LINUX) }) { Text(stringResource(R.string.workspace_detail_area_rootfs)) }
                        Button(onClick = onImport) { Text(stringResource(R.string.workspace_detail_import_file)) }
                        if (state.path.isNotBlank()) {
                            Button(onClick = onGoUp) { Text("..") }
                        }
                    }
                }
            }
        }
        if (state.entries.isEmpty() && !state.loading) {
            item {
                Text(stringResource(R.string.workspace_detail_empty_directory), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(state.entries, key = { it.path }) { entry ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(entry.name, style = MaterialTheme.typography.titleMedium)
                    Text(entry.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (entry.isDirectory) {
                            Button(onClick = { onOpen(entry) }) { Text(stringResource(R.string.open)) }
                        } else {
                            Button(onClick = { onExport(entry) }) { Text(stringResource(R.string.export)) }
                            Button(onClick = { onShare(entry) }) { Text(stringResource(R.string.share)) }
                        }
                        Button(onClick = { onDelete(entry) }) { Text(stringResource(R.string.delete)) }
                    }
                }
            }
        }
    }
}