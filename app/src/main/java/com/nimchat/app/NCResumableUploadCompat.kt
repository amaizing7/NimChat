package com.nimchat.app

import io.github.jan.supabase.storage.BucketApi
import io.github.jan.supabase.storage.createOrContinueUpload
import java.io.File

/** Streams the original Android Uri through the SDK's resumable uploader. */
suspend fun BucketApi.createOrContinueUpload(
    path: String,
    metadataFile: File
) = resumable.createOrContinueUpload(
    path,
    NCFeatureHelpers.sourceUri(metadataFile) ?: error("منبع فایل پیدا نشد")
)
