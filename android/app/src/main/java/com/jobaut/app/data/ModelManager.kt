package com.jobaut.app.data

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Manages local GGUF model files in app-private storage.
 *
 * Allows manual importing of Qwen and Reranker models from device storage
 * to keep the APK download size lightweight (~20MB) rather than bundling ~1GB in assets.
 */
object ModelManager {
    private const val TAG = "ModelManager"

    const val QWEN_MODEL_FILENAME = "qwen2.5-0.5b-instruct.gguf"
    const val RERANKER_MODEL_FILENAME = "bge-reranker-base.gguf"

    const val QWEN_DOWNLOAD_URL = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q8_0.gguf"
    const val RERANKER_DOWNLOAD_URL = "https://huggingface.co/magicunicorn/bge-reranker-base-Q8_0-GGUF/resolve/main/bge-reranker-base-q8_0.gguf"

    fun getModelsDir(context: Context): File {
        val dir = File(context.filesDir, "models")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getModelFile(context: Context, filename: String): File {
        return File(getModelsDir(context), filename)
    }

    fun isModelPresent(context: Context, filename: String): Boolean {
        val file = getModelFile(context, filename)
        return file.exists() && file.length() > 1024 * 1024 // at least 1MB
    }

    fun getModelSizeFormatted(context: Context, filename: String): String {
        val file = getModelFile(context, filename)
        if (!file.exists()) return "0 MB"
        val mb = file.length() / (1024 * 1024)
        return "$mb MB"
    }

    /**
     * Imports a GGUF model file from a SAF Uri into app's private files/models/ directory.
     */
    suspend fun importModel(
        context: Context,
        uri: Uri,
        targetFilename: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val targetFile = getModelFile(context, targetFilename)
            val tempFile = File(getModelsDir(context), "$targetFilename.tmp")

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(1024 * 64) // 64KB buffer
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            } ?: return@withContext Result.failure(Exception("Unable to open stream from URI"))

            if (tempFile.exists() && tempFile.length() > 0) {
                if (targetFile.exists()) {
                    targetFile.delete()
                }
                tempFile.renameTo(targetFile)
                Result.success(targetFile.absolutePath)
            } else {
                Result.failure(Exception("Imported file is empty"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error importing model: ${e.message}", e)
            Result.failure(e)
        }
    }

    fun deleteModel(context: Context, filename: String): Boolean {
        val file = getModelFile(context, filename)
        return if (file.exists()) file.delete() else true
    }
}
