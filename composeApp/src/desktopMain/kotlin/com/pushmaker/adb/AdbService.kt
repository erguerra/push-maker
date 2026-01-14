package com.pushmaker.adb

import com.pushmaker.model.PayloadMode
import com.pushmaker.model.PushPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.util.concurrent.TimeUnit

enum class DeviceState { ONLINE, OFFLINE, UNAUTHORIZED, UNKNOWN }

data class AdbDevice(
    val id: String,
    val description: String,
    val state: DeviceState
)

sealed interface AdbCommandResult {
    val message: String

    data class Success(override val message: String) : AdbCommandResult
    data class Failure(override val message: String) : AdbCommandResult
}

class AdbService(
    initialExecutable: String? = System.getenv("ADB")
) {
    @Volatile
    private var customExecutable: String? = initialExecutable?.takeIf { it.isNotBlank() }

    fun setExecutable(path: String?) {
        customExecutable = path?.takeIf { it.isNotBlank() }
    }

    fun currentExecutable(): String = resolveExecutable()

    suspend fun listDevices(): Result<List<AdbDevice>> = withContext(Dispatchers.IO) {
        runCatching {
            val output = runCommand(listOf(resolveExecutable(), "devices", "-l"))
            output.stdout
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("List of devices") }
                .mapNotNull(::parseDeviceLine)
                .toList()
        }
    }

    suspend fun sendPush(deviceId: String, payload: PushPayload): AdbCommandResult =
        withContext(Dispatchers.IO) {
            val executable = resolveExecutable()
            val args = buildList {
                addAll(listOf(executable, "-s", deviceId, "shell", "am", "broadcast"))
                addAll(listOf("-a", payload.action))
                if (payload.targetComponent.isNotBlank()) {
                    addAll(listOf("-n", payload.targetComponent))
                }
                when (payload.payloadMode) {
                    PayloadMode.STRUCTURED -> {
                        structuredExtras(payload).forEach { (key, value) ->
                            addAll(listOf("--es", key, quoteForShell(value)))
                        }
                    }
                    PayloadMode.RAW_JSON -> {
                        addAll(listOf("--es", "payload", quoteForShell(payload.rawJsonPayload)))
                    }
                }
            }
            val result = runCommand(args)
            if (result.exitCode == 0) {
                AdbCommandResult.Success(result.stdout.ifBlank { "Push broadcast sent" })
            } else {
                val message = buildString {
                    appendLine("ADB exited with ${result.exitCode}")
                    if (result.stdout.isNotBlank()) appendLine(result.stdout)
                    if (result.stderr.isNotBlank()) append(result.stderr)
                }.trim()
                AdbCommandResult.Failure(message.ifBlank { "ADB command failed" })
            }
        }

    private fun parseDeviceLine(line: String): AdbDevice? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        val tokens = trimmed.split(Regex("\\s+"))
        if (tokens.size < 2) return null
        val serial = tokens[0]
        val stateToken = tokens[1]
        val state = when {
            stateToken.contains("device", ignoreCase = true) -> DeviceState.ONLINE
            stateToken.contains("offline", ignoreCase = true) -> DeviceState.OFFLINE
            stateToken.contains("unauthorized", ignoreCase = true) -> DeviceState.UNAUTHORIZED
            else -> DeviceState.UNKNOWN
        }
        val description = tokens.drop(2).joinToString(" ")
        return AdbDevice(serial, description, state)
    }

    private fun runCommand(args: List<String>): ProcessResult {
        val process = ProcessBuilder(args)
            .redirectErrorStream(false)
            .start()
        process.waitFor(15, TimeUnit.SECONDS)
        val stdout = process.inputStream.bufferedReader().use(BufferedReader::readText)
        val stderr = process.errorStream.bufferedReader().use(BufferedReader::readText)
        return ProcessResult(process.exitValue(), stdout.trim(), stderr.trim())
    }

    private data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String)

    private fun resolveExecutable(): String = customExecutable ?: System.getenv("ADB") ?: "adb"
}

private fun quoteForShell(value: String): String {
    if (value.isEmpty()) return "''"
    val escaped = value.replace("'", "'\\''")
    return "'$escaped'"
}

private fun structuredExtras(payload: PushPayload): Map<String, String> {
    val map = linkedMapOf<String, String>()
    fun putIfNotBlank(key: String, value: String) {
        if (value.isNotBlank()) map[key] = value
    }
    putIfNotBlank("title", payload.title)
    putIfNotBlank("body", payload.body)
    putIfNotBlank("channelId", payload.channelId)
    putIfNotBlank("collapseKey", payload.collapseKey)
    putIfNotBlank("priority", payload.priority.name.lowercase())
    putIfNotBlank("icon", payload.icon)
    payload.metadata.forEach { field ->
        if (field.key.isNotBlank()) {
            map["metadata.${field.key}"] = field.value
        }
    }
    payload.dataFields.forEach { field ->
        if (field.key.isNotBlank()) {
            map["data.${field.key}"] = field.value
        }
    }
    return map
}
