package com.nimchat.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NCFeatureHelpersLargeFileTest {
    @Test fun standardLimitIs4GiB() {
        assertEquals(4L * 1024L * 1024L * 1024L, NCFeatureHelpers.MAX_STANDARD_BYTES)
    }

    @Test fun premiumLimitIs20GiB() {
        assertEquals(20L * 1024L * 1024L * 1024L, NCFeatureHelpers.MAX_PREMIUM_BYTES)
    }

    @Test fun imageLimitIs100MiB() {
        assertEquals(100L * 1024L * 1024L, NCFeatureHelpers.maxAttachmentBytes("image/jpeg", false))
    }

    @Test fun standardVideoAtLimitIsAccepted() {
        NCFeatureHelpers.validateAttachmentSize(NCFeatureHelpers.MAX_STANDARD_BYTES, "video/mp4", false)
    }

    @Test fun standardVideoAboveLimitIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            NCFeatureHelpers.validateAttachmentSize(NCFeatureHelpers.MAX_STANDARD_BYTES + 1, "video/mp4", false)
        }
    }

    @Test fun premiumFileAt20GiBIsAccepted() {
        NCFeatureHelpers.validateAttachmentSize(NCFeatureHelpers.MAX_PREMIUM_BYTES, "application/zip", true)
    }
}
