package com.nimchat.app

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

object NCFeatureHelpers {
    const val MAX_ATTACHMENT_BYTES = 10L * 1024L * 1024L

    fun validateAttachmentSize(size: Long) {
        require(size in 0..MAX_ATTACHMENT_BYTES) { "حجم فایل نباید بیشتر از ۱۰ مگابایت باشد" }
    }

    fun copyUriToCache(context: Context, uri: Uri, name: String): File {
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifBlank { "attachment" }
        val file = File(context.cacheDir, "nc_${System.currentTimeMillis()}_$safe")
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
                        validateAttachmentSize(total)
                        output.write(buffer, 0, read)
                    }
                }
            }
            validateAttachmentSize(file.length())
            return file
        } catch (e: Exception) {
            file.delete()
            throw e
        }
    }
}
