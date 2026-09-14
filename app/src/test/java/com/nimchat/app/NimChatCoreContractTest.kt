package com.nimchat.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NimChatCoreContractTest {
    private val usernamePattern = Regex("[a-z0-9_]{2,30}")

    @Test
    fun username_contract_accepts_valid_values_and_rejects_invalid_values() {
        assertTrue(usernamePattern.matches("ali_2026"))
        assertTrue(usernamePattern.matches("ab"))
        assertTrue(usernamePattern.matches("a".repeat(30)))
        assertFalse(usernamePattern.matches("Ari"))
        assertFalse(usernamePattern.matches("a-b"))
        assertFalse(usernamePattern.matches("a"))
        assertFalse(usernamePattern.matches("a".repeat(31)))
    }

    @Test
    fun message_contract_round_trips_id_and_edit_metadata() {
        val original = Message(
            id = "00000000-0000-0000-0000-000000000001",
            conversation_id = "00000000-0000-0000-0000-000000000002",
            sender_id = "00000000-0000-0000-0000-000000000003",
            body = "hello NimChat",
            created_at = "2026-09-14T22:00:00.000000Z",
            updated_at = "2026-09-14T22:01:00.000000Z"
        )
        val json = Json.encodeToString(original)
        val restored = Json.decodeFromString<Message>(json)
        assertEquals(original, restored)
        assertTrue(restored.updated_at != restored.created_at)
    }

    @Test
    fun message_body_contract_matches_server_limit() {
        assertTrue("x".repeat(1).length in 1..4000)
        assertTrue("x".repeat(4000).length in 1..4000)
        assertFalse("".length in 1..4000)
        assertFalse("x".repeat(4001).length in 1..4000)
    }
}
