package com.nimchat.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NCOutboxCodecTest {
    @Test
    fun failed_messages_round_trip_without_loss() {
        val messages = listOf(
            NCFailedMessage("m1", "c1", "u1", "hello", "network timeout"),
            NCFailedMessage("m2", "c2", "u1", "second", "offline")
        )

        val restored = NCOutboxCodec.decode(NCOutboxCodec.encode(messages))

        assertEquals(messages, restored)
    }

    @Test
    fun malformed_or_missing_storage_is_treated_as_empty() {
        assertTrue(NCOutboxCodec.decode(null).isEmpty())
        assertTrue(NCOutboxCodec.decode("").isEmpty())
        assertTrue(NCOutboxCodec.decode("not-json").isEmpty())
    }
}