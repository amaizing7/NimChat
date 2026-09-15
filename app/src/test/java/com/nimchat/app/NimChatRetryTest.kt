package com.nimchat.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NimChatRetryTest {
    @Test
    fun retry_is_allowed_for_same_conversation_and_nonblank_body() {
        val m = NCFailedMessage("id", "c1", "u1", "hello", "network")
        assertTrue(NimChatRetry.shouldRetry(m, "c1"))
    }

    @Test
    fun retry_is_rejected_for_other_conversation_or_blank_body() {
        val m = NCFailedMessage("id", "c1", "u1", "hello", "network")
        assertFalse(NimChatRetry.shouldRetry(m, "c2"))
        assertFalse(NimChatRetry.shouldRetry(m.copy(body = "   "), "c1"))
    }
}
