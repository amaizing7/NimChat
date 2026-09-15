package com.nimchat.app

import android.content.SharedPreferences
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

object NCOutboxCodec {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(NCFailedMessage.serializer())

    fun encode(messages: List<NCFailedMessage>): String =
        json.encodeToString(serializer, messages)

    fun decode(raw: String?): List<NCFailedMessage> = runCatching {
        if (raw.isNullOrBlank()) emptyList() else json.decodeFromString(serializer, raw)
    }.getOrDefault(emptyList())
}

class NCOutbox(private val prefs: SharedPreferences) {
    private val key = "pending_failed_messages"

    @Synchronized
    fun load(): List<NCFailedMessage> = NCOutboxCodec.decode(prefs.getString(key, null))

    @Synchronized
    fun loadForConversation(conversationId: String): List<NCFailedMessage> =
        load().filter { it.conversation_id == conversationId }

    @Synchronized
    fun save(message: NCFailedMessage) {
        val next = load().filterNot { it.id == message.id } + message
        prefs.edit().putString(key, NCOutboxCodec.encode(next)).apply()
    }

    @Synchronized
    fun remove(id: String) {
        val next = load().filterNot { it.id == id }
        prefs.edit().putString(key, NCOutboxCodec.encode(next)).apply()
    }
}
