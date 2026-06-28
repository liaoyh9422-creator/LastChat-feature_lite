package me.rerere.rikkahub.ui.pages.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.nav.OneUITopAppBar
import me.rerere.workspace.WorkspaceShellStatus
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun WorkspaceTerminalPage(id: String) {
    val vm: WorkspaceDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val state by vm.state.collectAsStateWithLifecycle()
    val terminalState by vm.terminalState.collectAsStateWithLifecycle()
    val shellReady = state.workspace?.shellStatus == WorkspaceShellStatus.READY.name

    Scaffold(
        topBar = {
            OneUITopAppBar(
                title = state.workspace?.name?.let {
                    stringResource(R.string.workspace_terminal_title_with_name, it)
                } ?: stringResource(R.string.workspace_terminal_title),
                scrollBehavior = androidx.compose.material3.TopAppBarDefaults.pinnedScrollBehavior(),
                navigationIcon = { BackButton() },
            )
        },
    ) { innerPadding ->
        val finished = terminalState.history.lastOrNull()?.let { it is WorkspaceTerminalEntry.Result || it is WorkspaceTerminalEntry.Error } == true
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (shellReady) {
                    stringResource(R.string.workspace_terminal_loading)
                } else {
                    stringResource(R.string.workspace_terminal_not_installed)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = terminalState.input,
                onValueChange = vm::updateTerminalInput,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.workspace_detail_tool_shell)) },
                enabled = shellReady && !terminalState.running,
            )
            Button(
                onClick = { vm.executeTerminalCommand(terminalState.input) },
                enabled = shellReady && !terminalState.running,
            ) {
                Text(stringResource(R.string.workspace_detail_tool_shell))
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(terminalState.history) { entry ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = when (entry) {
                                is WorkspaceTerminalEntry.Command -> "> ${entry.command}"
                                is WorkspaceTerminalEntry.Result -> buildString {
                                    appendLine("exit=${entry.result.exitCode}")
                                    if (entry.result.stdout.isNotBlank()) appendLine(entry.result.stdout)
                                    if (entry.result.stderr.isNotBlank()) appendLine(entry.result.stderr)
                                    if (entry.result.timedOut) appendLine("[timed out]")
                                }
                                is WorkspaceTerminalEntry.Error -> "Error: ${entry.message}"
                            },
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (finished) {
                    item {
                        Text(
                            text = stringResource(R.string.workspace_terminal_exited),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}