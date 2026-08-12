package com.turbodm.app.ui.downloads

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.turbodm.app.download.SchemeRegistry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddDownloadScreen(
    vm: AddDownloadViewModel = hiltViewModel(),
    initialUrl: String? = null,
    onAdded: () -> Unit,
    onCancel: () -> Unit,
    onMagnetPicker: (torrentId: Long) -> Unit
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val isMagnet = SchemeRegistry.extractScheme(state.url)?.equals("magnet", ignoreCase = true) == true
    // Show the audio-only switch when the URL's host is a known streaming site.
    // Kept as a small local check rather than depending on the registry —
    // matches the same host list as StreamingSchemeHandler.
    val isStreamingSite = remember(state.url) {
        val host = runCatching {
            SchemeRegistry.extractHost(state.url) ?: ""
        }.getOrDefault("")
        listOf(
            "youtube.com", "youtu.be", "tiktok.com", "instagram.com",
            "soundcloud.com", "bandcamp.com", "vimeo.com", "twitch.tv",
            "dailymotion.com", "reddit.com", "twitter.com", "x.com",
            "facebook.com", "fb.watch", "mediafire.com"
        ).any { host == it || host.endsWith(".$it") }
    }

    LaunchedEffect(initialUrl) { if (initialUrl != null) vm.setUrl(initialUrl) }
    LaunchedEffect(state.finished) { if (state.finished) onAdded() }
    LaunchedEffect(state.pendingMagnetId) {
        val id = state.pendingMagnetId
        if (id != null) {
            vm.onMagnetNavigated()
            onMagnetPicker(id)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add download") },
                navigationIcon = {
                    IconButton(onClick = onCancel) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = state.url,
                onValueChange = vm::setUrl,
                label = { Text("URL") },
                placeholder = { Text("https://example.com/file.zip  or  magnet:?xt=urn:btih:…") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Uri),
                isError = state.error != null,
                supportingText = { state.error?.let { Text(it) } },
                modifier = Modifier.fillMaxWidth()
            )
            // Hide the SHA-256 field for magnets — libtorrent verifies piece
            // hashes via SHA-1 automatically, and the user has nothing useful
            // to paste in for a magnet link.
            if (!isMagnet) {
                OutlinedTextField(
                    value = state.expectedSha256,
                    onValueChange = vm::setExpectedSha256,
                    label = { Text("Expected SHA-256 (optional)") },
                    placeholder = { Text("64 hex chars — paste from the source site") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Text),
                    supportingText = { Text("Leave blank to skip verification.") },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Text(
                    "Magnet link detected — you'll pick which files to download next.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            // Audio-only toggle: only show for streaming-site URLs.
            if (isStreamingSite && !isMagnet) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Audio only (MP3/Opus/M4A — best bitrate)")
                        Text(
                            "Saves to music/ subfolder if auto-categorize is on",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(checked = state.audioOnly, onCheckedChange = vm::setAudioOnly)
                }
            }
            // Optional schedule picker — pick an hour-of-day; the row will
            // sit in SCHEDULED until the wall clock reaches it.
            if (!isMagnet) {
                var scheduleEnabled by remember { mutableStateOf(state.scheduledHour != null) }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Schedule for later", modifier = Modifier.weight(1f))
                    Switch(
                        checked = scheduleEnabled,
                        onCheckedChange = {
                            scheduleEnabled = it
                            vm.setSchedule(if (it) state.scheduledHour ?: 22 else null, state.scheduledMinute)
                        }
                    )
                }
                if (scheduleEnabled) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Starts at")
                        // Quick hour picker — 0..23
                        var hour by remember { mutableStateOf(state.scheduledHour ?: 22) }
                        var minute by remember { mutableStateOf(state.scheduledMinute) }
                        OutlinedButton(onClick = {
                            hour = (hour - 1 + 24) % 24
                            vm.setSchedule(hour, minute)
                        }) { Text("−") }
                        Text(
                            "%02d:%02d".format(hour, minute),
                            style = MaterialTheme.typography.titleMedium
                        )
                        OutlinedButton(onClick = {
                            hour = (hour + 1) % 24
                            vm.setSchedule(hour, minute)
                        }) { Text("+") }
                        Text("h  :")
                        OutlinedButton(onClick = {
                            minute = (minute - 15 + 60) % 60
                            vm.setSchedule(hour, minute)
                        }) { Text("−") }
                        OutlinedButton(onClick = {
                            minute = (minute + 15) % 60
                            vm.setSchedule(hour, minute)
                        }) { Text("+") }
                        Text("m")
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = vm::submit,
                enabled = !state.isSubmitting && state.url.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.isSubmitting) CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                ) else {
                    val action = if (isMagnet) "Fetch torrent"
                    else if (state.scheduledHour != null) "Schedule download"
                    else "Start download"
                    Text(action)
                }
            }
            Text(
                "Files are saved to: /storage/emulated/0/Download/TurboDM — change in Settings.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
