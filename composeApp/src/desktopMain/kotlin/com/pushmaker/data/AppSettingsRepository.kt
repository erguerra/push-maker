package com.pushmaker.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

@Serializable
data class AppSettings(val adbPath: String? = null)

class AppSettingsRepository(
    private val json: Json = Json { prettyPrint = true; ignoreUnknownKeys = true }
) {
    private val mutex = Mutex()
    private val storageFile = LocalStoragePaths.settingsFile

    suspend fun get(): AppSettings = mutex.withLock {
        ensureFileExists()
        return withContext(Dispatchers.IO) {
            val content = storageFile.readText()
            if (content.isBlank()) AppSettings() else json.decodeFromString(AppSettings.serializer(), content)
        }
    }

    suspend fun save(settings: AppSettings) = mutex.withLock {
        ensureFileExists()
        withContext(Dispatchers.IO) {
            storageFile.writeText(json.encodeToString(AppSettings.serializer(), settings))
        }
    }

    private fun ensureFileExists() {
        if (!storageFile.exists()) {
            storageFile.parent?.createDirectories()
            Files.createFile(storageFile)
        }
    }
}
