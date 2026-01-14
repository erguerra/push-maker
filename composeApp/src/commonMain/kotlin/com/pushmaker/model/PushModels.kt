package com.pushmaker.model

import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlin.random.Random

@Serializable
enum class PushPriority { NORMAL, HIGH }

@Serializable
enum class PayloadMode { STRUCTURED, RAW_JSON }

@Serializable
data class KeyValueField(
    val id: String = randomId(),
    val key: String = "",
    val value: String = ""
)

@Serializable
data class PushPayload(
    val id: String = randomId(),
    val action: String = DEFAULT_PUSH_ACTION,
    val targetComponent: String = "",
    val name: String = "",
    val title: String = "",
    val body: String = "",
    val channelId: String = "",
    val collapseKey: String = "",
    val priority: PushPriority = PushPriority.HIGH,
    val icon: String = "",
    val metadata: List<KeyValueField> = emptyList(),
    val dataFields: List<KeyValueField> = emptyList(),
    val payloadMode: PayloadMode = PayloadMode.STRUCTURED,
    val rawJsonPayload: String = "",
    val createdAt: Long = nowMillis(),
    val updatedAt: Long = nowMillis()
)

fun PushPayload.touch(): PushPayload = copy(updatedAt = nowMillis())

private fun randomId(): String = buildString(capacity = 16) {
    repeat(16) {
        append("abcdef0123456789"[Random.nextInt(16)])
    }
}

private fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

const val DEFAULT_PUSH_ACTION = "com.pushmaker.DEBUG_PUSH"
