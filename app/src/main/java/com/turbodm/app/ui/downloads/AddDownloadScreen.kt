package com.turbodm.app.ui.downloads

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddDownloadScreen(
    vm: AddDownloadViewModel = hiltViewModel(),
    initialUrl: String? = null,
    onAdded: () -> Unit,
    onCancel: () -> Unit
) {
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(initialUrl) { if (initialUrl != null) vm.setUrl(initialUrl) }
    LaunchedEffect(state.finished) { if (state.finished) onAdded() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add download") },
                navigationIcon = {
                    IconButton(onClick = onCancel) { Icon(Icons.Default.ArrowBack, null) }
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
                placeholder = { Text("https://example.com/file.zip") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Uri),
                isError = state.error != null,
                supportingText = { state.error?.let { Text(it) } },
                modifier = Modifier.fillMaxWidth()
            )
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
                ) else Text("Start download")
            }
            Text(
                "Files are saved to: /storage/emulated/0/Download/TurboDM — change in Settings.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
