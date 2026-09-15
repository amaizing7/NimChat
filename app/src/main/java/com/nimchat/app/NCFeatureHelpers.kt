package com.nimchat.app

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

object NCFeatureHelpers {
    const val MAX_PHOTO_BYTES = 100L * 1024L * 1024L
    const val MAX_STANDARD_BYTES = 4L * 1024L * 1024L * 1024L
    const val MAX_PREMIUM_BYTES = 20L * 1024L * 1024L * 1024L
    const val MAX_ATTACHMENT_BYTES = MAX_STANDARD_BYTES

    fun maxAttachmentBytes(mime: String?, premium: Boolean): Long =
        if (mime.orEmpty().startsWith("image/")) MAX_PHOTO_BYTES
        else if (premium) MAX_PREMIUM_BYTES
        else MAX_STANDARD_BYTES

    fun validateAttachmentSize(size: Long, mime: String? = null, premium: Boolean = false) {
        require(size >= 0) { "حجم فایل نامعتبر است" }
        require(size <= maxAttachmentBytes(mime, premium)) { "حجم فایل از سقف مجاز بیشتر است" }
    }

    fun copyUriToCache(context: Context, uri: Uri, name: String, mime: String? = null, premium: Boolean = false): File {
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifBlank { "attachment" }
        val file = File(context.cacheDir, "nc_${System.currentTimeMillis()}_$safe")
        val limit = maxAttachmentBytes(mime, premium)
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "فایل قابل خواندن نیست" }
                FileOutputStream(file).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= limit) { "حجم فایل از سقف مجاز بیشتر است" }
                        output.write(buffer, 0, read)
                    }
                }
            }
            validateAttachmentSize(file.length(), mime, premium)
            return file
        } catch (e: Exception) {
            file.delete()
            throw e
        }
    }
}
