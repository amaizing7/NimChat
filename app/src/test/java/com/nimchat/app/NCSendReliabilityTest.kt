package com.nimchat.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NCSendReliabilityTest {
    @Test
    fun existing_message_with_same_id_means_send_already_succeeded() {
        val message = NCMessage("m1", "c1", "u1", "hello")
        assertTrue(NCSendReliability.isAlreadyDelivered(message, "m1"))
        assertFalse(NCSendReliability.shouldKeepOutboxAfterFailure(message, "m1"))
    }

    @Test
    fun missing_or_different_message_id_stays_in_outbox() {
        val message = NCMessage("m2", "c1", "u1", "hello")
        assertFalse(NCSendReliability.isAlreadyDelivered(message, "m1"))
        assertTrue(NCSendReliability.shouldKeepOutboxAfterFailure(message, "m1"))
        assertTrue(NCSendReliability.shouldKeepOutboxAfterFailure(null, "m1"))
    }
}
