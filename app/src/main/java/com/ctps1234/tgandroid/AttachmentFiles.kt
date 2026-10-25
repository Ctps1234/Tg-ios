package com.ctps1234.tgandroid

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.util.UUID

internal const val MAX_PHOTO_BYTES = 10L * 1024L * 1024L
internal const val MAX_DOCUMENT_BYTES = 2L * 1024L * 1024L * 1024L

/** Copy a picker URI to a private, stable path: TDLib InputFileLocal cannot read content:// URIs. */
internal fun copyPickedUri(context: Context, uri: Uri, maxBytes: Long): File {
    val resolver = context.contentResolver
    val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }.orEmpty()
    val safeName = displayName.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
        .take(100).ifBlank { "attachment" }
    val uploadDirectory = File(context.cacheDir, "telegram_uploads").apply { mkdirs() }
    val destination = File(uploadDirectory, "${UUID.randomUUID()}_$safeName")

    try {
        val input = resolver.openInputStream(uri) ?: throw IOException("Não foi possível abrir o arquivo selecionado.")
        input.use { source ->
            destination.outputStream().buffered().use { output ->
                val buffer = ByteArray(64 * 1024)
                var totalBytes = 0L
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    totalBytes += count
                    if (totalBytes > maxBytes) {
                        throw IOException("O anexo excede o limite de ${maxBytes / (1024 * 1024)} MB.")
                    }
                    output.write(buffer, 0, count)
                }
            }
        }
        if (destination.length() == 0L) throw IOException("O arquivo selecionado está vazio.")
        return destination
    } catch (failure: Exception) {
        destination.delete()
        throw failure
    }
}
