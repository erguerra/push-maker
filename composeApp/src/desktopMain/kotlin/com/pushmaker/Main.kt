package com.pushmaker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.pushmaker.adb.AdbDevice
import com.pushmaker.adb.AdbService
import com.pushmaker.data.AppSettingsRepository
import com.pushmaker.data.PushRepository
import com.pushmaker.model.KeyValueField
import com.pushmaker.model.PayloadMode
import com.pushmaker.model.PushPayload
import com.pushmaker.model.PushPriority
import kotlinx.coroutines.launch

fun main() = application {
    val controller = remember { PushMakerController(PushRepository(), AppSettingsRepository(), AdbService()) }
    DisposableEffect(Unit) {
        controller.initialize()
        onDispose { controller.dispose() }
    }
    Window(
        onCloseRequest = ::exitApplication,
        title = "Push Maker",
        state = rememberWindowState(width = 1200.dp, height = 800.dp)
    ) {
        PushMakerDesktopApp(controller)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PushMakerDesktopApp(controller: PushMakerController) {
    val uiState by controller.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(uiState.message) {
        val message = uiState.message ?: return@LaunchedEffect
        scope.launch {
            snackbarHostState.showSnackbar(message)
            controller.consumeMessage()
        }
    }

    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Push Maker", fontWeight = FontWeight.SemiBold) },
                    actions = {
                        DevicePicker(
                            devices = uiState.devices,
                            selectedId = uiState.selectedDeviceId,
                            refreshing = uiState.isRefreshingDevices,
                            onSelect = controller::selectDevice,
                            onRefresh = controller::refreshDevices
                        )
                        IconButton(onClick = controller::openSettings) {
                            Icon(
                                Icons.Filled.Settings,
                                contentDescription = "Settings",
                                tint = if (uiState.adbPath == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                )
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
        ) { padding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                SavedPushesPane(
                    pushes = uiState.savedPushes,
                    onSelect = controller::loadSavedPush,
                    onDelete = controller::removeSavedPush,
                    modifier = Modifier
                        .width(280.dp)
                        .fillMaxHeight()
                        .padding(16.dp)
                )
                VerticalDivider(modifier = Modifier.fillMaxHeight().width(1.dp))
                PushForm(
                    push = uiState.currentPush,
                    onChange = controller::updateCurrentPush,
                    onPriorityChange = controller::updatePriority,
                    onModeChange = controller::updatePayloadMode,
                    onRawJsonChange = controller::updateRawJsonPayload,
                    onImportRawJson = controller::importRawJsonFromFile,
                    onSend = controller::sendCurrentPush,
                    onSave = controller::saveCurrentPush,
                    onClear = controller::clearCurrentPush,
                    isSending = uiState.isSending,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(24.dp)
                )
            }
        }
        if (uiState.isSettingsOpen) {
            SettingsDialog(
                adbPathInput = uiState.settingsAdbPathInput,
                error = uiState.settingsError,
                onPathChange = controller::updateSettingsAdbPathInput,
                onBrowse = controller::browseAdbPathFromSettings,
                onDetect = controller::detectAdbPathFromSettings,
                onDownload = controller::openAdbDownloadPage,
                onSave = controller::saveAdbPathFromSettings,
                onDismiss = controller::closeSettings
            )
        }
    }
}

@Composable
private fun DevicePicker(
    devices: List<AdbDevice>,
    selectedId: String?,
    refreshing: Boolean,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val activeDevice = devices.firstOrNull { it.id == selectedId }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { expanded = true }, enabled = devices.isNotEmpty()) {
            Column(horizontalAlignment = Alignment.Start) {
                Text(activeDevice?.id ?: "Select device", fontWeight = FontWeight.SemiBold)
                if (activeDevice?.description?.isNotBlank() == true) {
                    Text(activeDevice.description, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (devices.isEmpty()) {
                DropdownMenuItem(text = { Text("No devices detected") }, onClick = { expanded = false })
            } else {
                devices.forEach { device ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(device.id, fontWeight = if (device.id == selectedId) FontWeight.Bold else FontWeight.Normal)
                                if (device.description.isNotBlank()) {
                                    Text(device.description, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        },
                        onClick = {
                            onSelect(device.id)
                            expanded = false
                        }
                    )
                }
            }
        }
        IconButton(onClick = onRefresh) {
            if (refreshing) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh devices")
            }
        }
    }
}

@Composable
private fun SavedPushesPane(
    pushes: List<PushPayload>,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(modifier = modifier.fillMaxHeight()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text("Saved pushes", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            if (pushes.isEmpty()) {
                Text("No saved pushes yet", color = MaterialTheme.colorScheme.outline)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(pushes, key = { it.id }) { push ->
                        SavedPushCard(push, onSelect, onDelete)
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedPushCard(push: PushPayload, onSelect: (String) -> Unit, onDelete: (String) -> Unit) {
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp)) {
            Text(push.name.ifBlank { "Untitled push" }, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(push.title.ifBlank { push.body.take(60) }, maxLines = 2, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSelect(push.id) }) { Text("Load") }
                OutlinedButton(onClick = { onDelete(push.id) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete push")
                }
            }
        }
    }
}

@Composable
private fun PushForm(
    push: PushPayload,
    onChange: ((PushPayload) -> PushPayload) -> Unit,
    onPriorityChange: (PushPriority) -> Unit,
    onModeChange: (PayloadMode) -> Unit,
    onRawJsonChange: (String) -> Unit,
    onImportRawJson: () -> Unit,
    onSend: () -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
    isSending: Boolean,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    Column(modifier = modifier.verticalScroll(scrollState)) {
        Text("Push content", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "Label your push for quick identification, then configure the broadcast details below.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = push.name,
            onValueChange = { value -> onChange { it.copy(name = value) } },
            label = { Text("Label / Friendly name (optional, required to save)") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = push.action,
            onValueChange = { value -> onChange { it.copy(action = value) } },
            label = { Text("Broadcast action (required)") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = push.targetComponent,
            onValueChange = { value -> onChange { it.copy(targetComponent = value) } },
            label = { Text("Target component (optional, e.g. com.example/.Receiver)") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Device selection + at least one of Title/Body are required to send. Name is only required when saving.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(12.dp))
        PayloadModeSelector(current = push.payloadMode, onModeChange = onModeChange)
        Spacer(Modifier.height(12.dp))
        if (push.payloadMode == PayloadMode.STRUCTURED) {
            StructuredPayloadEditor(push, onChange, onPriorityChange)
        } else {
            RawJsonEditor(push.rawJsonPayload, onRawJsonChange, onImportRawJson)
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onSend, enabled = !isSending) {
                Text(if (isSending) "Sending..." else "Send push")
            }
            OutlinedButton(onClick = onSave) { Text("Save this push") }
            OutlinedButton(onClick = onClear) { Text("Clear fields") }
        }
    }
}

@Composable
private fun StructuredPayloadEditor(
    push: PushPayload,
    onChange: ((PushPayload) -> PushPayload) -> Unit,
    onPriorityChange: (PushPriority) -> Unit
) {
    Column {
        OutlinedTextField(
            value = push.title,
            onValueChange = { value -> onChange { it.copy(title = value) } },
            label = { Text("Title (optional)") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = push.body,
            onValueChange = { value -> onChange { it.copy(body = value) } },
            label = { Text("Body (optional)") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = push.channelId,
                onValueChange = { value -> onChange { it.copy(channelId = value) } },
                label = { Text("Channel ID (optional)") },
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = push.collapseKey,
                onValueChange = { value -> onChange { it.copy(collapseKey = value) } },
                label = { Text("Collapse key (optional)") },
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = push.icon,
            onValueChange = { value -> onChange { it.copy(icon = value) } },
            label = { Text("Icon name (optional)") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        PrioritySelector(current = push.priority, onPriorityChange = onPriorityChange)
        Spacer(Modifier.height(16.dp))
        KeyValueListEditor(
            title = "Notification metadata (optional)",
            fields = push.metadata,
            onFieldsChange = { list -> onChange { it.copy(metadata = list) } }
        )
        Spacer(Modifier.height(12.dp))
        KeyValueListEditor(
            title = "Custom data (optional)",
            fields = push.dataFields,
            onFieldsChange = { list -> onChange { it.copy(dataFields = list) } }
        )
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun RawJsonEditor(
    json: String,
    onChange: (String) -> Unit,
    onImport: () -> Unit
) {
    Column {
        OutlinedTextField(
            value = json,
            onValueChange = onChange,
            label = { Text("JSON payload (sent as --es payload)") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 8
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onImport) { Text("Import JSON file") }
            TextButton(onClick = { onChange("") }) { Text("Clear JSON") }
        }
    }
}

@Composable
private fun PayloadModeSelector(current: PayloadMode, onModeChange: (PayloadMode) -> Unit) {
    Column {
        Text("Payload mode", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PayloadMode.values().forEach { mode ->
                val selected = mode == current
                val label = when (mode) {
                    PayloadMode.STRUCTURED -> "Structured (multiple extras)"
                    PayloadMode.RAW_JSON -> "Raw JSON"
                }
                if (selected) {
                    Button(onClick = { onModeChange(mode) }) { Text(label) }
                } else {
                    OutlinedButton(onClick = { onModeChange(mode) }) { Text(label) }
                }
            }
        }
    }
}

@Composable
private fun PrioritySelector(current: PushPriority, onPriorityChange: (PushPriority) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Priority")
        PushPriority.values().forEach { priority ->
            val label = priority.displayName()
            if (priority == current) {
                Button(onClick = { onPriorityChange(priority) }) { Text(label) }
            } else {
                OutlinedButton(onClick = { onPriorityChange(priority) }) { Text(label) }
            }
        }
    }
}

@Composable
private fun KeyValueListEditor(
    title: String,
    fields: List<KeyValueField>,
    onFieldsChange: (List<KeyValueField>) -> Unit
) {
    Column {
        Text(title, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        fields.forEach { field ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = field.key,
                    onValueChange = { value -> onFieldsChange(fields.replaceField(field.id, field.copy(key = value))) },
                    label = { Text("Key") },
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = field.value,
                    onValueChange = { value -> onFieldsChange(fields.replaceField(field.id, field.copy(value = value))) },
                    label = { Text("Value") },
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { onFieldsChange(fields.removeField(field.id)) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove field")
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        OutlinedButton(onClick = { onFieldsChange(fields + KeyValueField()) }) { Text("Add field") }
    }
}

private fun List<KeyValueField>.replaceField(id: String, newField: KeyValueField): List<KeyValueField> =
    map { if (it.id == id) newField else it }

private fun List<KeyValueField>.removeField(id: String): List<KeyValueField> {
    val filtered = filterNot { it.id == id }
    return if (filtered.isEmpty()) listOf(KeyValueField()) else filtered
}

private fun PushPriority.displayName(): String =
    name.lowercase().replaceFirstChar { it.uppercase() }

@Composable
private fun SettingsDialog(
    adbPathInput: String,
    error: String?,
    onPathChange: (String) -> Unit,
    onBrowse: () -> Unit,
    onDetect: () -> Unit,
    onDownload: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column {
                Text("Configure where Push Maker should look for the ADB executable.")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = adbPathInput,
                    onValueChange = onPathChange,
                    label = { Text("ADB executable path") },
                    modifier = Modifier.fillMaxWidth()
                )
                if (error != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(error, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = onBrowse) { Text("Browse…") }
                    TextButton(onClick = onDetect) { Text("Detect automatically") }
                    TextButton(onClick = onDownload) { Text("Download ADB") }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "If left blank, the app will attempt to use adb from your PATH.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        },
        confirmButton = {
            Button(onClick = onSave) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
