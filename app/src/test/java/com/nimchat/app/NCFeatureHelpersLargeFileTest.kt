package com.nimchat.app

import org.junit.Assert.assertEquals
import org.junit.Test

class NCFeatureHelpersLargeFileTest {
    @Test fun allUsersFileLimitIs4GiB() {
        assertEquals(4L * 1024L * 1024L * 1024L, NCFeatureHelpers.MAX_STANDARD_BYTES)
        assertEquals(
            NCFeatureHelpers.MAX_STANDARD_BYTES,
            NCFeatureHelpers.maxAttachmentBytes("application/zip")
        )
        assertEquals(
            NCFeatureHelpers.MAX_STANDARD_BYTES,
            NCFeatureHelpers.maxAttachmentBytes("application/zip", true)
        )
    }

    @Test fun imageLimitIs100MiB() {
        assertEquals(100L * 1024L * 1024L, NCFeatureHelpers.maxAttachmentBytes("image/jpeg"))
    }

    @Test fun standardVideoAtLimitIsAccepted() {
        NCFeatureHelpers.validateAttachmentSize(NCFeatureHelpers.MAX_STANDARD_BYTES, "video/mp4")
    }

    @Test(expected = IllegalArgumentException::class)
    fun standardVideoAboveLimitIsRejected() {
        NCFeatureHelpers.validateAttachmentSize(NCFeatureHelpers.MAX_STANDARD_BYTES + 1, "video/mp4")
    }
}
