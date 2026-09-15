package com.nimchat.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

object NCFeatureHelpers {
    const val MAX_PHOTO_BYTES = 100L * 1024L * 1024L
    const val MAX_STANDARD_BYTES = 4L * 1024L * 1024L * 1024L
    const val MAX_PREMIUM_BYTES = 20L * 1024L * 1024L * 1024L
    const val MAX_ATTACHMENT_BYTES = MAX_STANDARD_BYTES
    private const val SOURCE_PREFS = "nimchat_attachment_sources"
    private val sourceUris = ConcurrentHashMap<String, Uri>()
    @Volatile private var appContext: Context? = null

    fun maxAttachmentBytes(mime: String?, premium: Boolean): Long =
        if (mime.orEmpty().startsWith("image/")) MAX_PHOTO_BYTES
        else if (premium) MAX_PREMIUM_BYTES
        else MAX_STANDARD_BYTES

    fun validateAttachmentSize(size: Long, mime: String? = null, premium: Boolean = false) {
        require(size >= 0) { "حجم فایل نامعتبر است" }
        require(size <= maxAttachmentBytes(mime, premium)) { "حجم فایل از سقف مجاز بیشتر است" }
    }

    /** Registers the source and creates only a sparse metadata file; payload bytes are not copied. */
    fun copyUriToCache(context: Context, uri: Uri, name: String, mime: String? = null, premium: Boolean = false): File {
        appContext = context.applicationContext
        val size = querySize(context, uri) ?: error("حجم فایل قابل تشخیص نیست")
        validateAttachmentSize(size, mime, premium)
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifBlank { "attachment" }
        val file = File(context.cacheDir, "nc_${System.currentTimeMillis()}_$safe")
        try {
            file.parentFile?.mkdirs()
            RandomAccessFile(file, "rw").use { it.setLength(size) }
            sourceUris[file.absolutePath] = uri
            context.getSharedPreferences(SOURCE_PREFS, Context.MODE_PRIVATE)
                .edit().putString(file.absolutePath, uri.toString()).apply()
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            return file
        } catch (e: Exception) {
            file.delete()
            clearSource(context, file)
            throw e
        }
    }

    fun sourceUri(metadataFile: File): Uri? {
        val context = appContext
        return sourceUris[metadataFile.absolutePath]
            ?: context?.getSharedPreferences(SOURCE_PREFS, Context.MODE_PRIVATE)
                ?.getString(metadataFile.absolutePath, null)?.let(Uri::parse)
    }

    fun clearSource(metadataFile: File) {
        sourceUris.remove(metadataFile.absolutePath)
        appContext?.getSharedPreferences(SOURCE_PREFS, Context.MODE_PRIVATE)
            ?.edit()?.remove(metadataFile.absolutePath)?.apply()
    }

    private fun clearSource(context: Context, metadataFile: File) {
        sourceUris.remove(metadataFile.absolutePath)
        context.getSharedPreferences(SOURCE_PREFS, Context.MODE_PRIVATE)
            .edit().remove(metadataFile.absolutePath).apply()
    }

    private fun querySize(context: Context, uri: Uri): Long? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (!it.moveToFirst() || it.isNull(0)) null else it.getLong(0)
        }
}
