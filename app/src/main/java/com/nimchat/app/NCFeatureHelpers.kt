package com.nimchat.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

object NCFeatureHelpers {
    // Existing file content is intentionally preserved except for the nullable
    // SharedPreferences.Editor chain in clearSource(Context, File).
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
            ?.edit()?.remove(metadataFile.absolutePath)?.apply()
    }

    private fun querySize(context: Context, uri: Uri): Long? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (!it.moveToFirst() || it.isNull(0)) null else it.getLong(0)
        }
}
