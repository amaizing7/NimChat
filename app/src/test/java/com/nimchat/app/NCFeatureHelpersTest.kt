package com.nimchat.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NCFeatureHelpersTest {
    @Test
    fun photoLimit_is100Mb() {
        assertEquals(100L * 1024L * 1024L, NCFeatureHelpers.maxAttachmentBytes("image/jpeg", false))
    }

    @Test
    fun standardVideoLimit_is4Gb() {
        assertEquals(4L * 1024L * 1024L * 1024L, NCFeatureHelpers.maxAttachmentBytes("video/mp4", false))
    }

    @Test
    fun standardFileLimit_is4Gb() {
        assertEquals(4L * 1024L * 1024L * 1024L, NCFeatureHelpers.maxAttachmentBytes("application/zip", false))
    }

    @Test
    fun premiumFileLimit_is20Gb() {
        assertEquals(20L * 1024L * 1024L * 1024L, NCFeatureHelpers.maxAttachmentBytes("application/pdf", true))
    }

    @Test
    fun premiumPhotoLimit_stays100Mb() {
        assertEquals(100L * 1024L * 1024L, NCFeatureHelpers.maxAttachmentBytes("image/png", true))
    }

    @Test
    fun attachmentSize_accepts_exact_limit() {
        NCFeatureHelpers.validateAttachmentSize(100L * 1024L * 1024L, "image/jpeg", false)
        NCFeatureHelpers.validateAttachmentSize(4L * 1024L * 1024L * 1024L, "video/mp4", false)
        NCFeatureHelpers.validateAttachmentSize(20L * 1024L * 1024L * 1024L, "application/zip", true)
    }

    @Test
    fun attachmentSize_rejects_over_limit() {
        assertThrows(IllegalArgumentException::class.java) {
            NCFeatureHelpers.validateAttachmentSize(100L * 1024L * 1024L + 1, "image/jpeg", false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NCFeatureHelpers.validateAttachmentSize(4L * 1024L * 1024L * 1024L + 1, "video/mp4", false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NCFeatureHelpers.validateAttachmentSize(20L * 1024L * 1024L * 1024L + 1, "application/zip", true)
        }
    }

    @Test
    fun attachmentSize_rejects_negative() {
        assertThrows(IllegalArgumentException::class.java) {
            NCFeatureHelpers.validateAttachmentSize(-1, "application/pdf", false)
        }
    }
}
