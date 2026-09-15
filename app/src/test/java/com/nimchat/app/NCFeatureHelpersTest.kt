package com.nimchat.app

import org.junit.Assert.assertThrows
import org.junit.Test

class NCFeatureHelpersTest {
    @Test
    fun attachmentSize_accepts_exact_limit() {
        NCFeatureHelpers.validateAttachmentSize(NCFeatureHelpers.MAX_ATTACHMENT_BYTES)
    }

    @Test
    fun attachmentSize_rejects_over_limit() {
        assertThrows(IllegalArgumentException::class.java) {
            NCFeatureHelpers.validateAttachmentSize(NCFeatureHelpers.MAX_ATTACHMENT_BYTES + 1)
        }
    }

    @Test
    fun attachmentSize_rejects_negative() {
        assertThrows(IllegalArgumentException::class.java) {
            NCFeatureHelpers.validateAttachmentSize(-1)
        }
    }
}
