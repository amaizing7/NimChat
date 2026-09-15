package com.nimchat.app

import kotlinx.serialization.Serializable

@Serializable
data class NCFailedMessage(
    val id: String,
    val conversation_id: String,
    val sender_id: String,
    val body: String,
    val error: String
)

object NimChatRetry {
    fun shouldRetry(message: NCFailedMessage, activeConversationId: String): Boolean =
        message.conversation_id == activeConversationId && message.body.isNotBlank()
}
