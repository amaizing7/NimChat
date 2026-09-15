package com.nimchat.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NCFeatureHelpersTest {
    @Test
    fun photoLimit_is100MiB() {
        assertEquals(100L * 1024L * 1024L, NCFeatureHelpers.maxAttachmentBytes("image/jpeg"))
    }

    @Test
    fun videoLimit_is4GiBForEveryone() {
        assertEquals(4L * 1024L * 1024L * 1024L, NCFeatureHelpers.maxAttachmentBytes("video/mp4"))
    }

    @Test
    fun fileLimit_is4GiBForEveryone() {
        assertEquals(4L * 1024L * 1024L * 1024L, NCFeatureHelpers.maxAttachmentBytes("application/zip"))
    }

    @Test
    fun exactLimits_areAccepted() {
        NCFeatureHelpers.validateAttachmentSize(100L * 1024L * 1024L, "image/jpeg")
        NCFeatureHelpers.validateAttachmentSize(4L * 1024L * 1024L * 1024L, "video/mp4")
        NCFeatureHelpers.validateAttachmentSize(4L * 1024L * 1024L * 1024L, "application/zip")
    }

    @Test
    fun overLimits_areRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            NCFeatureHelpers.validateAttachmentSize(100L * 1024L * 1024L + 1, "image/jpeg")
        }
        assertThrows(IllegalArgumentException::class.java) {
            NCFeatureHelpers.validateAttachmentSize(4L * 1024L * 1024L * 1024L + 1, "video/mp4")
        }
        assertThrows(IllegalArgumentException::class.java) {
            NCFeatureHelpers.validateAttachmentSize(4L * 1024L * 1024L * 1024L + 1, "application/zip")
        }
    }

    @Test
    fun negativeSize_isRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            NCFeatureHelpers.validateAttachmentSize(-1, "application/pdf")
        }
    }
}
