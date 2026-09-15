package com.nimchat.app

import android.content.Context
import io.github.jan.supabase.storage.BucketApi
import io.github.jan.supabase.storage.createOrContinueUpload
import java.io.File

/**
 * Compatibility bridge for the existing attachment call site.
 * The SDK's resumable Uri overload streams from ContentResolver instead of reading a full
 * multi-GB payload into app cache.
 */
suspend fun BucketApi.createOrContinueUpload(
    path: String,
    metadataFile: File,
    context: Context
) = resumable.createOrContinueUpload(
    path,
    NCFeatureHelpers.sourceUri(context, metadataFile)
        ?: error("منبع فایل پیدا نشد")
)
