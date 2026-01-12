package com.pushmaker.data

import com.pushmaker.model.PushPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class PushRepository(
    private val json: Json = Json { prettyPrint = true; ignoreUnknownKeys = true }
) {
    private val serializer = ListSerializer(PushPayload.serializer())
    private val mutex = Mutex()
    private val storageFile = LocalStoragePaths.pushesFile

    suspend fun getAll(): List<PushPayload> = mutex.withLock { readAllUnlocked() }

    suspend fun upsert(push: PushPayload) = mutex.withLock {
        val current = readAllUnlocked()
        val updated = current.filterNot { it.id == push.id } + push
        writeAllUnlocked(updated)
    }

    suspend fun delete(id: String) = mutex.withLock {
        val current = readAllUnlocked()
        val updated = current.filterNot { it.id == id }
        writeAllUnlocked(updated)
    }

    private suspend fun readAllUnlocked(): List<PushPayload> {
        ensureFileExists()
        return withContext(Dispatchers.IO) {
            val content = storageFile.readText()
            if (content.isBlank()) emptyList() else json.decodeFromString(serializer, content)
        }
    }

    private suspend fun writeAllUnlocked(pushes: List<PushPayload>) {
        ensureFileExists()
        withContext(Dispatchers.IO) {
            storageFile.writeText(json.encodeToString(serializer, pushes))
        }
    }

    private fun ensureFileExists() {
        if (!storageFile.exists()) {
            storageFile.parent?.createDirectories()
            Files.createFile(storageFile)
        }
    }

}
