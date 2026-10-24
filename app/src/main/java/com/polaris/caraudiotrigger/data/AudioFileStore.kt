package com.polaris.caraudiotrigger.data

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException

data class ImportedAudio(
    val path: String,
    val displayName: String,
)

class AudioFileStore(private val context: Context) {
    private val audioDirectory = File(context.filesDir, "audio")

    fun ensureBuiltInChime(): File {
        val output = File(audioDirectory, DEFAULT_CHIME_FILE)
        if (!output.exists() || output.length() < 44L) {
            audioDirectory.mkdirs()
            DefaultChimeGenerator.write(output)
        }
        return output
    }

    fun import(uri: Uri): ImportedAudio {
        audioDirectory.mkdirs()
        val displayName = queryDisplayName(context.contentResolver, uri)
            .ifBlank { "自选音频" }
        val extension = displayName.substringAfterLast('.', "audio")
            .lowercase()
            .filter { it.isLetterOrDigit() }
            .take(8)
            .ifBlank { "audio" }
        val temporary = File(audioDirectory, "custom_audio.tmp")
        val destination = File(audioDirectory, "custom_audio.$extension")

        context.contentResolver.openInputStream(uri)?.use { input ->
            temporary.outputStream().buffered().use { output -> input.copyTo(output) }
        } ?: throw IOException("无法读取所选文件")

        if (temporary.length() == 0L) {
            temporary.delete()
            throw IOException("所选音频为空")
        }

        validateMedia(temporary)
        audioDirectory.listFiles { file ->
            file != temporary && file.name.startsWith("custom_audio.")
        }
            ?.forEach { it.delete() }
        if (!temporary.renameTo(destination)) {
            temporary.copyTo(destination, overwrite = true)
            temporary.delete()
        }
        return ImportedAudio(destination.absolutePath, displayName)
    }

    fun resolve(settings: AppSettings): File {
        if (settings.audioSource == AudioSource.CUSTOM) {
            val custom = File(settings.customAudioPath)
            if (custom.isFile && custom.length() > 0L) return custom
        }
        return ensureBuiltInChime()
    }

    private fun validateMedia(file: File) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val hasDuration = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION,
            )?.toLongOrNull()?.let { it > 0L } ?: false
            if (!hasDuration) throw IOException("无法识别该音频文件")
        } catch (error: Exception) {
            file.delete()
            if (error is IOException) throw error
            throw IOException("无法识别该音频文件", error)
        } finally {
            retriever.release()
        }
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String {
        val cursor: Cursor = resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        ) ?: return ""
        cursor.use {
            if (!it.moveToFirst()) return ""
            val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            return if (index >= 0) it.getString(index).orEmpty() else ""
        }
    }

    companion object {
        private const val DEFAULT_CHIME_FILE = "built_in_chime.wav"
    }
}
