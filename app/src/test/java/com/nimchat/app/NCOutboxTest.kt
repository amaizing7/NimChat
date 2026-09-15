package com.nimchat.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NCOutboxTest {
    @Test
    fun save_load_and_remove_are_persistent() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("outbox-test-${System.nanoTime()}", Context.MODE_PRIVATE)
        val outbox = NCOutbox(prefs)
        val message = NCFailedMessage("m1", "c1", "u1", "hello", "network")

        outbox.save(message)
        assertEquals(listOf(message), outbox.loadForConversation("c1"))
        assertTrue(outbox.loadForConversation("c2").isEmpty())

        outbox.save(message.copy(error = "timeout"))
        assertEquals("timeout", outbox.load().single().error)

        outbox.remove("m1")
        assertTrue(outbox.load().isEmpty())
    }
}
