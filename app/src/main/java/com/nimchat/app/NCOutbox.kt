package com.nimchat.app

import android.content.SharedPreferences
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class NCOutbox(private val prefs: SharedPreferences) {
    private val json = Json { ignoreUnknownKeys = true }
    private val key = "pending_failed_messages"

    @Synchronized
    fun load(): List<NCFailedMessage> = runCatching {
        val raw = prefs.getString(key, null) ?: return emptyList()
        json.decodeFromString(ListSerializer(NCFailedMessage.serializer()), raw)
    }.getOrDefault(emptyList())

    @Synchronized
    fun loadForConversation(conversationId: String): List<NCFailedMessage> =
        load().filter { it.conversation_id == conversationId }

    @Synchronized
    fun save(message: NCFailedMessage) {
        val next = load().filterNot { it.id == message.id } + message
        prefs.edit()
            .putString(key, json.encodeToString(ListSerializer(NCFailedMessage.serializer()), next))
            .apply()
    }

    @Synchronized
    fun remove(id: String) {
        val next = load().filterNot { it.id == id }
        prefs.edit()
            .putString(key, json.encodeToString(ListSerializer(NCFailedMessage.serializer()), next))
            .apply()
    }
}
