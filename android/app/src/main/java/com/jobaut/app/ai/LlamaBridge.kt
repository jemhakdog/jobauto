package com.jobaut.app.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * JNI Bridge to native llama.cpp library for on-device inference and scoring.
 */
object LlamaBridge {

    init {
        System.loadLibrary("llama-bridge")
    }

    // Native external declarations
    external fun nativeInitModel(modelPath: String, nThreads: Int, ctxSize: Int): Long
    external fun nativeGenerate(ctxPtr: Long, prompt: String, maxTokens: Int): String
    external fun nativeScore(ctxPtr: Long, query: String, doc: String): Float
    external fun nativeFree(ctxPtr: Long)

    /**
     * Initializes the llama.cpp model from the specified GGUF file path.
     * Runs off the main thread on Dispatchers.Default.
     *
     * @param modelPath Path to the .gguf model file
     * @param nThreads Number of CPU execution threads (defaults to 4)
     * @param ctxSize Context window size (defaults to 1024)
     * @return Opaque pointer (jlong) to the native context, or 0 on failure
     */
    suspend fun init(
        modelPath: String,
        nThreads: Int = 4,
        ctxSize: Int = 1024
    ): Long = withContext(Dispatchers.Default) {
        nativeInitModel(modelPath, nThreads, ctxSize)
    }

    /**
     * Alias for [init] matching task brief naming.
     */
    suspend fun initModel(
        path: String,
        nThreads: Int = 4,
        ctxSize: Int = 1024
    ): Long = init(path, nThreads, ctxSize)

    /**
     * Generates a text response for the given prompt.
     * Runs off the main thread on Dispatchers.Default.
     *
     * @param ctxPtr Native context pointer obtained from [init]
     * @param prompt Input prompt text
     * @param maxTokens Maximum number of tokens to predict (defaults to 128)
     * @return Generated text string
     */
    suspend fun generate(
        ctxPtr: Long,
        prompt: String,
        maxTokens: Int = 128
    ): String = withContext(Dispatchers.Default) {
        if (ctxPtr == 0L) return@withContext ""
        nativeGenerate(ctxPtr, prompt, maxTokens)
    }

    /**
     * Computes relevance/similarity score between query and document.
     * Runs off the main thread on Dispatchers.Default.
     *
     * @param ctxPtr Native context pointer
     * @param query Search query or requirement
     * @param doc Candidate text or job description
     * @return Similarity score between 0.0 and 1.0
     */
    suspend fun score(
        ctxPtr: Long,
        query: String,
        doc: String
    ): Float = withContext(Dispatchers.Default) {
        if (ctxPtr == 0L) return@withContext 0.0f
        nativeScore(ctxPtr, query, doc)
    }

    /**
     * Alias for [score] matching task brief naming.
     */
    suspend fun scoreRelevance(
        ctxPtr: Long,
        query: String,
        doc: String
    ): Float = score(ctxPtr, query, doc)

    /**
     * Frees native model and context memory.
     * Runs off the main thread on Dispatchers.Default.
     *
     * @param ctxPtr Native context pointer to release
     */
    suspend fun free(ctxPtr: Long) = withContext(Dispatchers.Default) {
        if (ctxPtr != 0L) {
            nativeFree(ctxPtr)
        }
    }

    /**
     * Alias for [free] matching task brief naming.
     */
    suspend fun freeModel(ctxPtr: Long) = free(ctxPtr)
}
