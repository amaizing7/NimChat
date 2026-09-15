package com.nimchat.app

/**
 * Pure decision helpers for retrying message sends safely.
 * A timed-out insert may already have reached Postgres, so a retry must
 * first treat an existing row with the same id as success.
 */
object NCSendReliability {
    fun isAlreadyDelivered(existing: NCMessage?, messageId: String): Boolean =
        existing?.id == messageId

    fun shouldKeepOutboxAfterFailure(existing: NCMessage?, messageId: String): Boolean =
        !isAlreadyDelivered(existing, messageId)
}
