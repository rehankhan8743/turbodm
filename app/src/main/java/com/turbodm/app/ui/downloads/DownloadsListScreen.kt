package com.turbodm.app.ui.downloads

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.turbodm.app.domain.model.Download
import com.turbodm.app.domain.model.DownloadStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsListScreen(
    vm: DownloadsListViewModel = hiltViewModel(),
    onAdd: () -> Unit,
    onSettings: () -> Unit
) {
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val filter = remember { mutableStateOf(Filter.All) }
    val visible = remember(downloads, filter.value) {
        when (filter.value) {
            Filter.All -> downloads
            Filter.Active -> downloads.filter { it.status.isActive || it.status == DownloadStatus.PAUSED || it.status == DownloadStatus.QUEUED }
            Filter.Done -> downloads.filter { it.status == DownloadStatus.COMPLETED }
            Filter.Failed -> downloads.filter { it.status == DownloadStatus.FAILED || it.status == DownloadStatus.CANCELLED }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TurboDM") },
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, null) }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Add URL") }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            FilterChips(filter)
            if (visible.isEmpty()) EmptyState()
            else LazyColumn(Modifier.fillMaxSize()) {
                items(visible, key = { it.id }) { d ->
                    DownloadRow(
                        d = d,
                        onPause = { vm.pause(d) },
                        onResume = { vm.resume(d) },
                        onCancel = { vm.cancel(d) },
                        onDelete = { vm.delete(d) }
                    )
                    Divider()
                }
            }
        }
    }
}

private enum class Filter(val label: String) {
    All("All"), Active("Active"), Done("Done"), Failed("Failed")
}

@Composable
private fun FilterChips(selected: MutableState<Filter>) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Filter.values().forEach { f ->
            FilterChip(
                selected = selected.value == f,
                onClick = { selected.value = f },
                label = { Text(f.label) }
            )
        }
    }
}

@Composable
private fun DownloadRow(
    d: Download,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(d.fileName, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(
                    "${humanBytes(d.downloadedBytes)} / ${humanBytes(d.totalBytes)}" +
                        if (d.totalBytes <= 0) "" else "  ·  ${(d.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            AssistChip(onClick = {}, label = { Text(d.status.name) })
            Spacer(Modifier.width(4.dp))
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, null) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (d.status.isActive) DropdownMenuItem(text = { Text("Pause") }, onClick = { menuOpen = false; onPause() })
                    if (d.status == DownloadStatus.PAUSED || d.status == DownloadStatus.QUEUED || d.status == DownloadStatus.FAILED)
                        DropdownMenuItem(text = { Text("Resume") }, onClick = { menuOpen = false; onResume() })
                    DropdownMenuItem(text = { Text("Cancel") }, onClick = { menuOpen = false; onCancel() })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menuOpen = false; onDelete() })
                }
            }
        }
        if (d.status == DownloadStatus.DOWNLOADING || d.status == DownloadStatus.PAUSED) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { d.progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth()
            )
        }
        d.errorMessage?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun EmptyState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No downloads yet", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text("Tap “Add URL” to start.", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun humanBytes(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
    b < 1024L * 1024L * 1024L -> "%.1f MB".format(b / 1024.0 / 1024.0)
    else -> "%.2f GB".format(b / 1024.0 / 1024.0 / 1024.0)
}

private val DownloadStatus.isActive: Boolean
    get() = this == DownloadStatus.ANALYZING || this == DownloadStatus.DOWNLOADING
