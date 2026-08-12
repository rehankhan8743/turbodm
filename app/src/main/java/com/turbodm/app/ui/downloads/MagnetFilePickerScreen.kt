package com.turbodm.app.ui.downloads

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.turbodm.app.domain.model.DownloadStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MagnetFilePickerScreen(
    vm: MagnetFilePickerViewModel = hiltViewModel(),
    onDone: () -> Unit,
    onCancel: () -> Unit
) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.finished) { if (state.finished) onDone() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pick files") },
                navigationIcon = {
                    IconButton(onClick = onCancel) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when (state.status) {
                DownloadStatus.ANALYZING, DownloadStatus.QUEUED -> {
                    FetchingMetadata()
                }
                DownloadStatus.FAILED -> {
                    ErrorBanner(state.errorMessage)
                }
                else -> {
                    Header(state)
                    SelectionToolbar(
                        selectedCount = state.selectedCount,
                        totalCount = state.files.size,
                        onSelectAll = vm::selectAll,
                        onSelectNone = vm::selectNone
                    )
                    HorizontalDivider()
                    LazyColumn(Modifier.weight(1f)) {
                        items(state.files, key = { it.index }) { row ->
                            FileRowItem(
                                row = row,
                                onToggle = { vm.toggleFile(row.index) }
                            )
                            HorizontalDivider()
                        }
                    }
                    state.errorMessage?.let { ErrorBanner(it) }
                    BottomBar(
                        isStarting = state.isStarting,
                        enabled = state.selectedCount > 0 && !state.isStarting,
                        onStart = vm::start
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(state: MagnetFilePickerViewModel.UiState) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(state.name.ifBlank { "Torrent" }, style = MaterialTheme.typography.titleMedium)
        Text(
            "Total: ${humanBytes(state.totalBytes)}",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun SelectionToolbar(
    selectedCount: Int,
    totalCount: Int,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("$selectedCount / $totalCount selected", modifier = Modifier.weight(1f))
        TextButton(onClick = onSelectAll) { Text("All") }
        TextButton(onClick = onSelectNone) { Text("None") }
    }
}

@Composable
private fun FileRowItem(
    row: MagnetFilePickerViewModel.FileRow,
    onToggle: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = row.selected, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f)) {
            Text(row.path, style = MaterialTheme.typography.bodyMedium)
            Text(humanBytes(row.size), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun BottomBar(isStarting: Boolean, enabled: Boolean, onStart: () -> Unit) {
    Surface(tonalElevation = 4.dp) {
        Button(
            onClick = onStart,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            if (isStarting) CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary
            ) else Text("Download selected files")
        }
    }
}

@Composable
private fun FetchingMetadata() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(
            "Fetching torrent metadata…",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
private fun ErrorBanner(message: String?) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            message ?: "Something went wrong.",
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

private fun humanBytes(bytes: Long): String = when {
    bytes < 0 -> "—"
    bytes < 1024 -> "$bytes B"
    bytes < 1024L * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> "${bytes / (1024L * 1024)} MB"
    else -> "%.1f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
}
