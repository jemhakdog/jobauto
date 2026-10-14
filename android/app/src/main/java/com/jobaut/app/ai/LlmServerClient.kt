package com.jobaut.app.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.exp

/**
 * Connection status for the remote LLM and Reranker server.
 */
data class ServerStatus(
    val isConnected: Boolean = false,
    val llmModel: String = "",
    val rerankerModel: String = "",
    val message: String = ""
)

/**
 * Client for communicating with the external LLM and Reranker server
 * (e.g. llama.cpp server, Ollama, or local host server).
 *
 * Automatically detects and loads the two required models:
 * 1. Operations / Question Answering LLM (Qwen / instruct)
 * 2. Scoring / Reranker Cross-Encoder (BGE Reranker or LLM verifier)
 */
object LlmServerClient {
    private const val TAG = "LlmServerClient"

    @Volatile
    var activeLlmModel: String = ""
        private set

    @Volatile
    var activeRerankerModel: String = ""
        private set

    @Volatile
    var lastServerStatus: ServerStatus = ServerStatus()
        private set

    /**
     * Connects to the server URL, verifies connectivity, and automatically
     * discovers / loads the two required models.
     */
    suspend fun checkConnection(serverUrl: String, rerankerUrl: String = ""): ServerStatus = withContext(Dispatchers.IO) {
        val cleanServer = serverUrl.trim().removeSuffix("/")
        if (cleanServer.isBlank()) {
            val status = ServerStatus(false, "", "", "Server URL is not configured")
            lastServerStatus = status
            return@withContext status
        }

        try {
            // 1. Fetch available models from /v1/models
            val models = fetchModels(cleanServer)
            var llm = ""
            var reranker = ""

            if (models.isNotEmpty()) {
                // Find chat / operations model (prioritize Qwen or instruct)
                llm = models.firstOrNull { it.contains("qwen", ignoreCase = true) }
                    ?: models.firstOrNull { it.contains("instruct", ignoreCase = true) || it.contains("chat", ignoreCase = true) }
                    ?: models.first()

                // Check if reranker model is listed on the same server
                reranker = models.firstOrNull { it.contains("rerank", ignoreCase = true) || it.contains("bge", ignoreCase = true) } ?: ""
            }

            // 2. If reranker wasn't on primary server, probe dedicated reranker endpoint or port 8081
            val effectiveReranker = when {
                rerankerUrl.isNotBlank() -> rerankerUrl.trim().removeSuffix("/")
                cleanServer.contains(":8080") -> cleanServer.replace(":8080", ":8081")
                else -> cleanServer
            }

            if (reranker.isEmpty()) {
                try {
                    val rModels = fetchModels(effectiveReranker)
                    reranker = rModels.firstOrNull { it.contains("rerank", ignoreCase = true) || it.contains("bge", ignoreCase = true) }
                        ?: if (rModels.isNotEmpty()) rModels.first() else ""
                } catch (_: Exception) {
                    // Fallback to LLM fit verification if separate reranker endpoint is inactive
                }
            }

            // 3. If /v1/models was empty or unsupported, test /health
            if (llm.isBlank()) {
                if (checkHealth(cleanServer)) {
                    llm = "Active (llama.cpp server)"
                }
            }

            if (llm.isNotBlank()) {
                activeLlmModel = llm
                activeRerankerModel = reranker.ifBlank { "LLM Fit Verifier ($llm)" }

                // Warmup / auto-load chat model if Ollama or model loading endpoint is supported
                autoLoadModel(cleanServer, activeLlmModel)

                val status = ServerStatus(
                    isConnected = true,
                    llmModel = activeLlmModel,
                    rerankerModel = activeRerankerModel,
                    message = "Connected to $cleanServer"
                )
                lastServerStatus = status
                return@withContext status
            } else {
                val status = ServerStatus(
                    isConnected = false,
                    message = "Could not detect models at $cleanServer"
                )
                lastServerStatus = status
                return@withContext status
            }
        } catch (e: Exception) {
            Log.w(TAG, "Connection check failed: ${e.message}")
            val status = ServerStatus(
                isConnected = false,
                message = e.message ?: "Connection error"
            )
            lastServerStatus = status
            return@withContext status
        }
    }

    /**
     * Generates a completion for the prompt via OpenAI-compatible chat completion endpoint.
     */
    suspend fun generate(
        prompt: String,
        maxTokens: Int = 128,
        serverUrl: String = "http://127.0.0.1:8080"
    ): String = withContext(Dispatchers.IO) {
        val cleanServer = serverUrl.trim().removeSuffix("/")
        val endpoint = "$cleanServer/v1/chat/completions"

        try {
            val payload = JSONObject().apply {
                if (activeLlmModel.isNotBlank()) {
                    put("model", activeLlmModel)
                }
                put("temperature", 0.0)
                put("max_tokens", maxTokens)
                val messages = JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", "You are a strictly factual job application assistant. You never fabricate experience and only choose from the provided options.")
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                }
                put("messages", messages)
                put("chat_template_kwargs", JSONObject().apply {
                    put("enable_thinking", false)
                })
            }

            val responseStr = httpPost(endpoint, payload.toString(), timeoutMs = 15000)
            val json = JSONObject(responseStr)
            val choices = json.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val message = choices.getJSONObject(0).optJSONObject("message")
                var content = message?.optString("content") ?: ""
                if (content.isBlank()) {
                    content = message?.optString("reasoning_content") ?: ""
                }
                // Strip <think> tags if present
                content = content.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "").trim()
                return@withContext content
            }
            ""
        } catch (e: Exception) {
            Log.w(TAG, "Chat completion failed on $endpoint: ${e.message}")
            ""
        }
    }

    /**
     * Scores candidate-job fit using dedicated reranker endpoint or fallback LLM verifier.
     */
    suspend fun scoreRelevance(
        query: String,
        doc: String,
        serverUrl: String = "http://127.0.0.1:8080",
        rerankerUrl: String = ""
    ): Float = withContext(Dispatchers.IO) {
        val cleanServer = serverUrl.trim().removeSuffix("/")
        val effectiveReranker = when {
            rerankerUrl.isNotBlank() -> rerankerUrl.trim().removeSuffix("/")
            cleanServer.contains(":8080") -> cleanServer.replace(":8080", ":8081")
            else -> cleanServer
        }

        // 1. Try dedicated /rerank endpoint first (e.g. BGE reranker cross-encoder)
        val rerankEndpoint = "$effectiveReranker/rerank"
        try {
            val payload = JSONObject().apply {
                put("query", query)
                put("documents", JSONArray().put(doc))
            }
            val resp = httpPost(rerankEndpoint, payload.toString(), timeoutMs = 10000)
            val json = JSONObject(resp)
            val results = json.optJSONArray("results")
            if (results != null && results.length() > 0) {
                val rawScore = results.getJSONObject(0).optDouble("relevance_score", 0.0)
                // Sigmoid conversion into 0.0 - 1.0 probability
                val prob = (1.0 / (1.0 + exp(-rawScore))).toFloat()
                return@withContext prob.coerceIn(0.0f, 1.0f)
            }
        } catch (_: Exception) {
            // Reranker endpoint not available, fall through to LLM evaluation
        }

        // 2. Fallback: Verify job match using LLM prompt via /v1/chat/completions (matches Python job_verifier.py)
        try {
            val verifyPrompt = "Candidate Profile: $query\nJob Details: $doc\nTask: Is this entry-level/junior candidate a fit? Respond ONLY with JSON: {\"match\": true/false, \"score\": integer 0-100}"
            val llmResponse = generate(verifyPrompt, maxTokens = 60, serverUrl = cleanServer)
            if (llmResponse.isNotBlank()) {
                val match = Regex("\"match\"\\s*:\\s*(true|false)", RegexOption.IGNORE_CASE).find(llmResponse)?.groupValues?.get(1)?.toBoolean()
                val score = Regex("\"score\"\\s*:\\s*(\\d+)", RegexOption.IGNORE_CASE).find(llmResponse)?.groupValues?.get(1)?.toIntOrNull()
                if (score != null) {
                    return@withContext (score / 100.0f).coerceIn(0.0f, 1.0f)
                }
                if (match != null) {
                    return@withContext if (match) 0.8f else 0.2f
                }
            }
        } catch (_: Exception) {
        }

        // Neutral pass fallback if server is offline
        0.5f
    }

    private fun fetchModels(baseUrl: String): List<String> {
        val endpoint = "$baseUrl/v1/models"
        val models = mutableListOf<String>()
        try {
            val resp = httpGet(endpoint, timeoutMs = 3000)
            val json = JSONObject(resp)
            val data = json.optJSONArray("data")
            if (data != null) {
                for (i in 0 until data.length()) {
                    val id = data.getJSONObject(i).optString("id")
                    if (id.isNotBlank()) models.add(id)
                }
            }
            val legacyModels = json.optJSONArray("models")
            if (legacyModels != null) {
                for (i in 0 until legacyModels.length()) {
                    val name = legacyModels.getJSONObject(i).optString("name")
                    if (name.isNotBlank() && !models.contains(name)) models.add(name)
                }
            }
        } catch (_: Exception) {
        }
        return models
    }

    private fun checkHealth(baseUrl: String): Boolean {
        return try {
            val resp = httpGet("$baseUrl/health", timeoutMs = 2000)
            resp.contains("ok", ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    private fun autoLoadModel(baseUrl: String, modelName: String) {
        // For Ollama servers, a warmup call loads the model into VRAM/RAM
        try {
            val payload = JSONObject().apply {
                put("model", modelName)
                put("prompt", "")
                put("keep_alive", "1h")
            }
            httpPost("$baseUrl/api/generate", payload.toString(), timeoutMs = 2000)
        } catch (_: Exception) {
        }
    }

    private fun httpGet(urlStr: String, timeoutMs: Int): String {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.setRequestProperty("User-Agent", "JobAutAndroid/1.0")
        return conn.inputStream.bufferedReader().use(BufferedReader::readText)
    }

    private fun httpPost(urlStr: String, jsonBody: String, timeoutMs: Int): String {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        conn.setRequestProperty("User-Agent", "JobAutAndroid/1.0")
        conn.outputStream.bufferedWriter().use { it.write(jsonBody) }
        return conn.inputStream.bufferedReader().use(BufferedReader::readText)
    }
}
