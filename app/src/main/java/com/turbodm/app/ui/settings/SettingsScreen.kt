package com.turbodm.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: SettingsViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val s by vm.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } }
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SettingSection("Network") {
                IntStepper("Max parallel downloads", s.maxParallel, 1..8) { vm.setMaxParallel(it) }
                SwitchRow("Wi-Fi only", s.wifiOnly) { vm.setWifiOnly(it) }
                LongStepper("Speed limit (KB/s, 0 = off)", s.speedLimitBps / 1024L, 0..100_000) {
                    vm.setSpeedLimitBps(it * 1024L)
                }
                IntStepper("Default segments", s.defaultSegments, 1..16) { vm.setSegments(it) }
            }
            SettingSection("Storage") {
                OutlinedTextField(
                    value = s.downloadDir,
                    onValueChange = vm::setDownloadDir,
                    label = { Text("Download folder") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            SettingSection("HTTP") {
                OutlinedTextField(
                    value = s.userAgent,
                    onValueChange = vm::setUserAgent,
                    label = { Text("User-Agent") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Text(
                "Phase-1 MVP. More options arrive with queue manager, scheduler, rules, and integrity hashing.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun SettingSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun IntStepper(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f))
            IconButton(onClick = { onChange((value - 1).coerceIn(range)) }) { Text("−") }
            Text("$value", style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = { onChange((value + 1).coerceIn(range)) }) { Text("+") }
        }
    }
}

@Composable
private fun LongStepper(label: String, value: Long, range: LongRange, onChange: (Long) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f))
            IconButton(onClick = { onChange((value - 256L).coerceIn(range)) }) { Text("−") }
            Text("$value", style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = { onChange((value + 256L).coerceIn(range)) }) { Text("+") }
        }
    }
}
