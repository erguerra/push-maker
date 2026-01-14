package com.pushmaker

import com.pushmaker.adb.AdbCommandResult
import com.pushmaker.adb.AdbDevice
import com.pushmaker.adb.AdbLocator
import com.pushmaker.adb.AdbService
import com.pushmaker.data.AppSettings
import com.pushmaker.data.AppSettingsRepository
import com.pushmaker.data.PushRepository
import com.pushmaker.model.DEFAULT_PUSH_ACTION
import com.pushmaker.model.KeyValueField
import com.pushmaker.model.PayloadMode
import com.pushmaker.model.PushPayload
import com.pushmaker.model.PushPriority
import com.pushmaker.model.touch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

fun blankPush(): PushPayload = ensureEditable(PushPayload())

data class PushMakerUiState(
    val devices: List<AdbDevice> = emptyList(),
    val selectedDeviceId: String? = null,
    val currentPush: PushPayload = blankPush(),
    val savedPushes: List<PushPayload> = emptyList(),
    val isSending: Boolean = false,
    val isRefreshingDevices: Boolean = false,
    val message: String? = null,
    val adbPath: String? = null,
    val isSettingsOpen: Boolean = false,
    val settingsAdbPathInput: String = "",
    val settingsError: String? = null
)

class PushMakerController(
    private val repository: PushRepository,
    private val settingsRepository: AppSettingsRepository,
    private val adbService: AdbService,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val _uiState = MutableStateFlow(PushMakerUiState())
    val uiState = _uiState.asStateFlow()

    fun initialize() {
        scope.launch { loadSavedPushes() }
        scope.launch { ensureAdbPathConfigured() }
        refreshDevices()
    }

    fun refreshDevices() {
        scope.launch {
            _uiState.value = _uiState.value.copy(isRefreshingDevices = true)
            val result = adbService.listDevices()
            result.fold(
                onSuccess = { devices ->
                    val currentSelected = _uiState.value.selectedDeviceId
                    val newSelected = currentSelected?.takeIf { id -> devices.any { it.id == id } }
                        ?: devices.firstOrNull()?.id
                    _uiState.value = _uiState.value.copy(
                        devices = devices,
                        selectedDeviceId = newSelected,
                        isRefreshingDevices = false,
                        message = if (devices.isEmpty()) "No ADB devices detected" else null
                    )
                },
                onFailure = { throwable ->
                    _uiState.value = _uiState.value.copy(
                        devices = emptyList(),
                        selectedDeviceId = null,
                        isRefreshingDevices = false,
                        message = "ADB error: ${throwable.message ?: "unknown error"}"
                    )
                }
            )
        }
    }

    fun selectDevice(id: String) {
        _uiState.value = _uiState.value.copy(selectedDeviceId = id)
    }

    fun updateCurrentPush(transform: (PushPayload) -> PushPayload) {
        _uiState.value = _uiState.value.copy(currentPush = ensureEditable(transform(_uiState.value.currentPush)))
    }

    fun clearCurrentPush() {
        _uiState.value = _uiState.value.copy(currentPush = blankPush())
    }

    fun loadSavedPush(id: String) {
        val target = _uiState.value.savedPushes.firstOrNull { it.id == id } ?: return
        _uiState.value = _uiState.value.copy(currentPush = ensureEditable(target))
    }

    fun removeSavedPush(id: String) {
        scope.launch {
            repository.delete(id)
            loadSavedPushes()
            _uiState.value = _uiState.value.copy(message = "Push deleted")
        }
    }

    fun saveCurrentPush() {
        val current = sanitize(_uiState.value.currentPush)
        if (current.name.isBlank()) {
            _uiState.value = _uiState.value.copy(message = "Name is required before saving")
            return
        }
        scope.launch {
            val stored = current.touch()
            repository.upsert(stored)
            loadSavedPushes()
            _uiState.value = _uiState.value.copy(
                currentPush = ensureEditable(stored),
                message = "Push saved"
            )
        }
    }

    fun sendCurrentPush() {
        val state = _uiState.value
        val targetDevice = state.selectedDeviceId
        if (targetDevice == null) {
            _uiState.value = state.copy(message = "Select a device before sending")
            return
        }
        val payload = sanitize(state.currentPush)
        if (payload.payloadMode == PayloadMode.STRUCTURED && payload.title.isBlank() && payload.body.isBlank()) {
            _uiState.value = state.copy(message = "Add at least a title or body")
            return
        }
        if (payload.payloadMode == PayloadMode.RAW_JSON && payload.rawJsonPayload.isBlank()) {
            _uiState.value = state.copy(message = "Provide JSON payload content")
            return
        }
        scope.launch {
            _uiState.value = _uiState.value.copy(isSending = true)
            when (val result = adbService.sendPush(targetDevice, payload)) {
                is AdbCommandResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isSending = false,
                        message = result.message.ifBlank { "Push sent" }
                    )
                }
                is AdbCommandResult.Failure -> {
                    _uiState.value = _uiState.value.copy(
                        isSending = false,
                        message = result.message
                    )
                }
            }
        }
    }

    private suspend fun loadSavedPushes() {
        val pushes = repository.getAll()
            .map(::ensureEditable)
            .sortedByDescending { it.updatedAt }
        _uiState.value = _uiState.value.copy(savedPushes = pushes)
    }

    fun updatePriority(priority: PushPriority) {
        updateCurrentPush { it.copy(priority = priority) }
    }

    fun updatePayloadMode(mode: PayloadMode) {
        updateCurrentPush { it.copy(payloadMode = mode) }
    }

    fun updateRawJsonPayload(value: String) {
        updateCurrentPush { it.copy(rawJsonPayload = value) }
    }

    fun importRawJsonFromFile() {
        scope.launch {
            val file = chooseFileForRawJson() ?: return@launch
            runCatching { file.readText() }
                .onSuccess { updateRawJsonPayload(it) }
                .onFailure { err -> _uiState.update { state -> state.copy(message = "Failed to read file: ${err.message}") } }
        }
    }

    fun consumeMessage() {
        if (_uiState.value.message != null) {
            _uiState.value = _uiState.value.copy(message = null)
        }
    }

    fun dispose() {
        scope.cancel()
    }

    fun openSettings() {
        val currentInput = _uiState.value.adbPath ?: _uiState.value.settingsAdbPathInput
        _uiState.update { it.copy(isSettingsOpen = true, settingsAdbPathInput = currentInput, settingsError = null) }
    }

    fun closeSettings() {
        _uiState.update { it.copy(isSettingsOpen = false, settingsError = null) }
    }

    fun updateSettingsAdbPathInput(value: String) {
        _uiState.update { it.copy(settingsAdbPathInput = value) }
    }

    fun saveAdbPathFromSettings() {
        val input = _uiState.value.settingsAdbPathInput.trim()
        val sanitized = input.takeIf { it.isNotBlank() }
        if (sanitized != null && !AdbLocator.isExecutable(sanitized)) {
            _uiState.update { it.copy(settingsError = "File not found or not executable") }
            return
        }
        scope.launch {
            applyAdbPath(sanitized, persist = true)
            _uiState.update {
                it.copy(
                    isSettingsOpen = false,
                    settingsError = null,
                    message = sanitized?.let { path -> "Using ADB at $path" } ?: "Using adb from PATH"
                )
            }
            refreshDevices()
        }
    }

    fun detectAdbPathFromSettings() {
        scope.launch {
            val detected = AdbLocator.detectAdbExecutable()
            if (detected != null) {
                _uiState.update { it.copy(settingsAdbPathInput = detected, settingsError = null) }
            } else {
                _uiState.update { it.copy(settingsError = "Could not locate platform-tools automatically") }
            }
        }
    }

    fun browseAdbPathFromSettings() {
        scope.launch {
            val selection = chooseAdbExecutableUsingDialog()
            if (selection != null) {
                _uiState.update { it.copy(settingsAdbPathInput = selection, settingsError = null) }
            }
        }
    }

    fun openAdbDownloadPage() {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val uri = URI("https://developer.android.com/studio/releases/platform-tools")
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().browse(uri)
                } else {
                    throw IllegalStateException("Desktop browsing not supported")
                }
            }.onFailure { error ->
                _uiState.update { it.copy(message = "Failed to open browser: ${error.message}") }
            }
        }
    }

    private suspend fun ensureAdbPathConfigured() {
        val settings = settingsRepository.get()
        val saved = settings.adbPath?.takeIf { AdbLocator.isExecutable(it) }
        val resolved = saved ?: AdbLocator.detectAdbExecutable()
        if (resolved != null) {
            applyAdbPath(resolved, persist = saved == null)
        } else {
            _uiState.update {
                it.copy(
                    adbPath = null,
                    settingsAdbPathInput = "",
                    message = "ADB not found. Set the path in settings."
                )
            }
        }
    }

    private suspend fun applyAdbPath(path: String?, persist: Boolean) {
        adbService.setExecutable(path)
        if (persist) {
            settingsRepository.save(AppSettings(path))
        }
        _uiState.update {
            it.copy(
                adbPath = path,
                settingsAdbPathInput = path.orEmpty(),
                settingsError = null
            )
        }
    }

    private fun chooseFileForRawJson(): File? {
        val reference = AtomicReference<File?>()
        return try {
            SwingUtilities.invokeAndWait {
                val chooser = JFileChooser().apply {
                    fileSelectionMode = JFileChooser.FILES_ONLY
                    dialogTitle = "Select JSON payload"
                }
                val result = chooser.showOpenDialog(null)
                if (result == JFileChooser.APPROVE_OPTION) {
                    reference.set(chooser.selectedFile)
                }
            }
            reference.get()
        } catch (error: Exception) {
            _uiState.update { it.copy(message = "File picker error: ${error.message}") }
            null
        }
    }

    private fun chooseAdbExecutableUsingDialog(): String? {
        val resultRef = AtomicReference<String?>()
        return try {
            SwingUtilities.invokeAndWait {
                val chooser = JFileChooser().apply {
                    fileSelectionMode = JFileChooser.FILES_ONLY
                    dialogTitle = "Select adb executable"
                }
                val result = chooser.showOpenDialog(null)
                if (result == JFileChooser.APPROVE_OPTION) {
                    resultRef.set(chooser.selectedFile.absolutePath)
                }
            }
            resultRef.get()
        } catch (error: Exception) {
            _uiState.update { it.copy(message = "File picker error: ${error.message}") }
            null
        }
    }
}

private fun ensureEditable(payload: PushPayload): PushPayload = payload.copy(
    action = payload.action.ifBlank { DEFAULT_PUSH_ACTION },
    targetComponent = payload.targetComponent.trim(),
    metadata = payload.metadata.takeUnless { it.isEmpty() } ?: listOf(KeyValueField()),
    dataFields = payload.dataFields.takeUnless { it.isEmpty() } ?: listOf(KeyValueField()),
    payloadMode = payload.payloadMode,
    rawJsonPayload = payload.rawJsonPayload
)

private fun sanitize(payload: PushPayload): PushPayload = payload.copy(
    action = payload.action.ifBlank { DEFAULT_PUSH_ACTION },
    targetComponent = payload.targetComponent.trim(),
    metadata = payload.metadata.filter { it.key.isNotBlank() || it.value.isNotBlank() },
    dataFields = payload.dataFields.filter { it.key.isNotBlank() || it.value.isNotBlank() },
    rawJsonPayload = payload.rawJsonPayload.trim()
)
