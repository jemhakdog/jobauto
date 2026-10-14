package com.jobaut.app.ai

/**
 * Compatibility bridge forwarding calls to [LlmServerClient].
 * Native JNI/C++ llama.cpp compilation has been removed in favor of HTTP server connectivity.
 */
object LlamaBridge {

    suspend fun init(
        modelPath: String = "",
        nThreads: Int = 4,
        ctxSize: Int = 1024,
        isReranker: Boolean = false
    ): Long {
        return 1L // Non-zero mock context pointer for backward compatibility
    }

    suspend fun initModel(
        path: String = "",
        nThreads: Int = 4,
        ctxSize: Int = 1024,
        isReranker: Boolean = false
    ): Long = init(path, nThreads, ctxSize, isReranker)

    suspend fun generate(
        ctxPtr: Long = 0L,
        prompt: String,
        maxTokens: Int = 128
    ): String {
        return LlmServerClient.generate(prompt, maxTokens)
    }

    suspend fun score(
        ctxPtr: Long = 0L,
        query: String,
        doc: String
    ): Float {
        return LlmServerClient.scoreRelevance(query, doc)
    }

    suspend fun scoreRelevance(
        ctxPtr: Long = 0L,
        query: String,
        doc: String
    ): Float = score(ctxPtr, query, doc)

    suspend fun free(ctxPtr: Long) {
        // No-op for HTTP server
    }

    suspend fun freeModel(ctxPtr: Long) = free(ctxPtr)
}
