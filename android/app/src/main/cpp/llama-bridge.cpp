#include <jni.h>
#include <string>
#include <vector>
#include <cmath>
#include <algorithm>
#include <android/log.h>

#include "llama.h"

#define TAG "LlamaBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct BridgeContext {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
};

// Helper: Tokenize text using llama_tokenize
std::vector<llama_token> tokenize(const llama_model* model, const std::string& text, bool add_special, bool parse_special = false) {
    int32_t n_tokens_max = static_cast<int32_t>(text.length()) + 2 * (add_special ? 1 : 0) + 8;
    std::vector<llama_token> tokens(n_tokens_max);
    int32_t n_tokens = llama_tokenize(model, text.c_str(), static_cast<int32_t>(text.length()), tokens.data(), static_cast<int32_t>(tokens.size()), add_special, parse_special);
    if (n_tokens < 0) {
        tokens.resize(-n_tokens);
        n_tokens = llama_tokenize(model, text.c_str(), static_cast<int32_t>(text.length()), tokens.data(), static_cast<int32_t>(tokens.size()), add_special, parse_special);
    }
    if (n_tokens > 0) {
        tokens.resize(n_tokens);
    } else {
        tokens.clear();
    }
    return tokens;
}

// Helper: Convert single token to piece string
std::string token_to_piece(const llama_model* model, llama_token token, bool special = false) {
    std::string piece;
    piece.resize(32);
    int32_t n_chars = llama_token_to_piece(model, token, &piece[0], static_cast<int32_t>(piece.size()), 0, special);
    if (n_chars < 0) {
        piece.resize(-n_chars);
        n_chars = llama_token_to_piece(model, token, &piece[0], static_cast<int32_t>(piece.size()), 0, special);
    }
    if (n_chars > 0) {
        piece.resize(n_chars);
    } else {
        piece.clear();
    }
    return piece;
}

// Helper: Cosine similarity between two float vectors
float cosine_similarity(const float* vecA, const float* vecB, size_t dim) {
    if (!vecA || !vecB || dim == 0) {
        return 0.0f;
    }
    double dot = 0.0;
    double normA = 0.0;
    double normB = 0.0;
    for (size_t i = 0; i < dim; ++i) {
        dot += static_cast<double>(vecA[i]) * static_cast<double>(vecB[i]);
        normA += static_cast<double>(vecA[i]) * static_cast<double>(vecA[i]);
        normB += static_cast<double>(vecB[i]) * static_cast<double>(vecB[i]);
    }
    double denom = std::sqrt(normA) * std::sqrt(normB);
    if (denom < 1e-12) {
        return 0.0f;
    }
    return static_cast<float>(dot / denom);
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_jobaut_app_ai_LlamaBridge_nativeInitModel(
        JNIEnv* env,
        jobject /* thiz */,
        jstring jModelPath,
        jint nThreads,
        jint ctxSize) {
    if (!jModelPath) {
        LOGE("nativeInitModel: modelPath is null");
        return 0;
    }

    const char* modelPathChars = env->GetStringUTFChars(jModelPath, nullptr);
    if (!modelPathChars) {
        LOGE("nativeInitModel: failed to get model path string UTF chars");
        return 0;
    }
    std::string modelPath(modelPathChars);
    env->ReleaseStringUTFChars(jModelPath, modelPathChars);

    LOGI("nativeInitModel: loading model from %s (threads=%d, ctxSize=%d)", modelPath.c_str(), nThreads, ctxSize);

    llama_backend_init();

    llama_model_params model_params = llama_model_default_params();
    llama_model* model = llama_load_model_from_file(modelPath.c_str(), model_params);
    if (!model) {
        LOGE("nativeInitModel: failed to load model from %s", modelPath.c_str());
        return 0;
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = static_cast<uint32_t>(ctxSize > 0 ? ctxSize : 1024);
    ctx_params.n_threads = static_cast<int32_t>(nThreads > 0 ? nThreads : 4);
    ctx_params.n_threads_batch = static_cast<int32_t>(nThreads > 0 ? nThreads : 4);

    llama_context* ctx = llama_new_context_with_model(model, ctx_params);
    if (!ctx) {
        LOGE("nativeInitModel: failed to create llama_context");
        llama_free_model(model);
        return 0;
    }

    auto* bridgeCtx = new BridgeContext();
    bridgeCtx->model = model;
    bridgeCtx->ctx = ctx;

    LOGI("nativeInitModel: successfully initialized bridge context at %p", bridgeCtx);
    return reinterpret_cast<jlong>(bridgeCtx);
}

JNIEXPORT jstring JNICALL
Java_com_jobaut_app_ai_LlamaBridge_nativeGenerate(
        JNIEnv* env,
        jobject /* thiz */,
        jlong ctxPtr,
        jstring jPrompt,
        jint maxTokens) {
    if (ctxPtr == 0) {
        LOGE("nativeGenerate: invalid context pointer 0");
        return env->NewStringUTF("");
    }

    auto* bridgeCtx = reinterpret_cast<BridgeContext*>(ctxPtr);
    if (!bridgeCtx || !bridgeCtx->model || !bridgeCtx->ctx) {
        LOGE("nativeGenerate: corrupted BridgeContext");
        return env->NewStringUTF("");
    }

    if (!jPrompt) {
        LOGE("nativeGenerate: prompt is null");
        return env->NewStringUTF("");
    }

    const char* promptChars = env->GetStringUTFChars(jPrompt, nullptr);
    if (!promptChars) {
        return env->NewStringUTF("");
    }
    std::string promptStr(promptChars);
    env->ReleaseStringUTFChars(jPrompt, promptChars);

    llama_model* model = bridgeCtx->model;
    llama_context* ctx = bridgeCtx->ctx;

    llama_kv_cache_clear(ctx);

    std::vector<llama_token> promptTokens = tokenize(model, promptStr, true, true);
    if (promptTokens.empty()) {
        LOGW("nativeGenerate: prompt tokenization resulted in 0 tokens");
        return env->NewStringUTF("");
    }

    const uint32_t n_ctx = llama_n_ctx(ctx);
    if (promptTokens.size() >= n_ctx) {
        LOGE("nativeGenerate: prompt tokens (%zu) exceed context window (%u)", promptTokens.size(), n_ctx);
        return env->NewStringUTF("");
    }

    llama_batch batch = llama_batch_init(static_cast<int32_t>(n_ctx), 0, 1);

    // Feed prompt tokens into batch
    for (size_t i = 0; i < promptTokens.size(); ++i) {
        batch.token[i] = promptTokens[i];
        batch.pos[i] = static_cast<llama_pos>(i);
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i] = (i + 1 == promptTokens.size()) ? 1 : 0;
    }
    batch.n_tokens = static_cast<int32_t>(promptTokens.size());

    if (llama_decode(ctx, batch) != 0) {
        LOGE("nativeGenerate: initial prompt llama_decode failed");
        llama_batch_free(batch);
        return env->NewStringUTF("");
    }

    // Sampler setup: greedy sampling
    auto sparams = llama_sampler_chain_default_params();
    llama_sampler* smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    std::string generatedText;
    int32_t n_cur = static_cast<int32_t>(promptTokens.size());
    int32_t tokensToPredict = maxTokens > 0 ? maxTokens : 128;

    for (int32_t step = 0; step < tokensToPredict && n_cur < static_cast<int32_t>(n_ctx); ++step) {
        llama_token new_token_id = llama_sampler_sample(smpl, ctx, -1);
        llama_sampler_accept(smpl, new_token_id);

        if (llama_token_is_eog(model, new_token_id)) {
            break;
        }

        generatedText += token_to_piece(model, new_token_id);

        // Prepare batch for next step
        batch.n_tokens = 0;
        batch.token[0] = new_token_id;
        batch.pos[0] = static_cast<llama_pos>(n_cur);
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = 1;
        batch.n_tokens = 1;

        n_cur++;

        if (llama_decode(ctx, batch) != 0) {
            LOGE("nativeGenerate: llama_decode failed at step %d", step);
            break;
        }
    }

    llama_sampler_free(smpl);
    llama_batch_free(batch);

    return env->NewStringUTF(generatedText.c_str());
}

JNIEXPORT jfloat JNICALL
Java_com_jobaut_app_ai_LlamaBridge_nativeScore(
        JNIEnv* env,
        jobject /* thiz */,
        jlong ctxPtr,
        jstring jQuery,
        jstring jDoc) {
    if (ctxPtr == 0) {
        LOGE("nativeScore: invalid context pointer 0");
        return 0.0f;
    }

    auto* bridgeCtx = reinterpret_cast<BridgeContext*>(ctxPtr);
    if (!bridgeCtx || !bridgeCtx->model || !bridgeCtx->ctx) {
        LOGE("nativeScore: corrupted BridgeContext");
        return 0.0f;
    }

    if (!jQuery || !jDoc) {
        LOGE("nativeScore: query or doc is null");
        return 0.0f;
    }

    const char* queryChars = env->GetStringUTFChars(jQuery, nullptr);
    const char* docChars = env->GetStringUTFChars(jDoc, nullptr);
    if (!queryChars || !docChars) {
        if (queryChars) env->ReleaseStringUTFChars(jQuery, queryChars);
        if (docChars) env->ReleaseStringUTFChars(jDoc, docChars);
        return 0.0f;
    }

    std::string queryStr(queryChars);
    std::string docStr(docChars);
    env->ReleaseStringUTFChars(jQuery, queryChars);
    env->ReleaseStringUTFChars(jDoc, docChars);

    llama_model* model = bridgeCtx->model;
    llama_context* ctx = bridgeCtx->ctx;

    llama_kv_cache_clear(ctx);

    std::vector<llama_token> queryTokens = tokenize(model, queryStr, true, true);
    std::vector<llama_token> docTokens = tokenize(model, docStr, true, true);

    if (queryTokens.empty() || docTokens.empty()) {
        LOGW("nativeScore: query or doc tokenization is empty");
        return 0.0f;
    }

    const int32_t n_embd = llama_n_embd(model);
    const uint32_t n_ctx = llama_n_ctx(ctx);

    // Try computing embedding vector for query
    std::vector<float> queryEmb(n_embd, 0.0f);
    std::vector<float> docEmb(n_embd, 0.0f);

    llama_batch batch = llama_batch_init(static_cast<int32_t>(n_ctx), 0, 1);

    // 1. Process query
    uint32_t qLen = static_cast<uint32_t>(std::min(queryTokens.size(), static_cast<size_t>(n_ctx)));
    for (uint32_t i = 0; i < qLen; ++i) {
        batch.token[i] = queryTokens[i];
        batch.pos[i] = static_cast<llama_pos>(i);
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i] = (i + 1 == qLen) ? 1 : 0;
    }
    batch.n_tokens = static_cast<int32_t>(qLen);

    bool hasEmbeddings = false;
    if (llama_decode(ctx, batch) == 0) {
        float* emb = llama_get_embeddings_seq(ctx, 0);
        if (!emb) {
            emb = llama_get_embeddings_ith(ctx, -1);
        }
        if (emb) {
            std::copy(emb, emb + n_embd, queryEmb.begin());
            hasEmbeddings = true;
        }
    }

    if (hasEmbeddings) {
        // 2. Process doc
        llama_kv_cache_clear(ctx);
        uint32_t dLen = static_cast<uint32_t>(std::min(docTokens.size(), static_cast<size_t>(n_ctx)));
        for (uint32_t i = 0; i < dLen; ++i) {
            batch.token[i] = docTokens[i];
            batch.pos[i] = static_cast<llama_pos>(i);
            batch.n_seq_id[i] = 1;
            batch.seq_id[i][0] = 0;
            batch.logits[i] = (i + 1 == dLen) ? 1 : 0;
        }
        batch.n_tokens = static_cast<int32_t>(dLen);

        if (llama_decode(ctx, batch) == 0) {
            float* emb = llama_get_embeddings_seq(ctx, 0);
            if (!emb) {
                emb = llama_get_embeddings_ith(ctx, -1);
            }
            if (emb) {
                std::copy(emb, emb + n_embd, docEmb.begin());
                llama_batch_free(batch);
                return cosine_similarity(queryEmb.data(), docEmb.data(), n_embd);
            }
        }
    }

    llama_batch_free(batch);

    // Fallback if model is generative and context has no embeddings enabled:
    // Compute lexical / token overlap Jaccard similarity score
    std::vector<llama_token> qSorted = queryTokens;
    std::vector<llama_token> dSorted = docTokens;
    std::sort(qSorted.begin(), qSorted.end());
    qSorted.erase(std::unique(qSorted.begin(), qSorted.end()), qSorted.end());
    std::sort(dSorted.begin(), dSorted.end());
    dSorted.erase(std::unique(dSorted.begin(), dSorted.end()), dSorted.end());

    std::vector<llama_token> intersection;
    std::set_intersection(qSorted.begin(), qSorted.end(),
                          dSorted.begin(), dSorted.end(),
                          std::back_inserter(intersection));

    size_t unionSize = qSorted.size() + dSorted.size() - intersection.size();
    if (unionSize == 0) {
        return 0.0f;
    }

    float jaccard = static_cast<float>(intersection.size()) / static_cast<float>(unionSize);
    return jaccard;
}

JNIEXPORT void JNICALL
Java_com_jobaut_app_ai_LlamaBridge_nativeFree(
        JNIEnv* /* env */,
        jobject /* thiz */,
        jlong ctxPtr) {
    if (ctxPtr == 0) {
        return;
    }

    auto* bridgeCtx = reinterpret_cast<BridgeContext*>(ctxPtr);
    if (!bridgeCtx) {
        return;
    }

    LOGI("nativeFree: freeing bridge context at %p", bridgeCtx);

    if (bridgeCtx->ctx) {
        llama_free(bridgeCtx->ctx);
        bridgeCtx->ctx = nullptr;
    }

    if (bridgeCtx->model) {
        llama_free_model(bridgeCtx->model);
        bridgeCtx->model = nullptr;
    }

    delete bridgeCtx;
}

} // extern "C"
