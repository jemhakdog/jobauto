# Fix APK Native C++ Inference & Bot Parity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the performance, inference correctness, and automation behavioral discrepancies between the Python bot and the native Android APK.

**Architecture:** 
1. Fix `llama-bridge.cpp` to correctly configure causal LLM decoding (`embeddings = false` for Qwen) and cross-encoder rank pooling for BGE Reranker (`LLAMA_POOLING_TYPE_RANK` with single-sequence query+doc evaluation).
2. Format on-device Qwen inference prompts using standard ChatML (`<|im_start|>...<|im_end|>`).
3. Ensure `JobVerifier.kt` accurately maps cross-encoder logits and falls back gracefully when reranking is not available.
4. Add Home search bar keyword entry and "New to you" tab switching in `BotEngine.kt` to match the Python bot's discovery flow.

**Tech Stack:** C++17, Android NDK, llama.cpp, Kotlin, Room, Coroutines, JUnit.

## Global Constraints

1. Keep JNI method signatures compatible or update both `llama-bridge.cpp` and `LlamaBridge.kt` atomically.
2. Ensure `ctx_params.embeddings` is ONLY set for embedding/reranker models, NEVER for generative causal LLMs (Qwen).
3. The APK build and unit tests (`./gradlew testDebugUnitTest`) must succeed.
4. Maintain backward compatibility with existing Room DB entities and UserProfile preferences.

---

### Task 1: Native C++ Reranker & LLM Mode Parity

**Files:**
- Modify: `android/app/src/main/cpp/llama-bridge.cpp`
- Modify: `android/app/src/main/java/com/jobaut/app/ai/LlamaBridge.kt`

**Interfaces:**
- `nativeInitModel(modelPath: String, nThreads: Int, ctxSize: Int, isReranker: Boolean): Long`
- `nativeScore(ctxPtr: Long, query: String, doc: String): Float` -> returns sigmoid probability [0.0, 1.0] from cross-encoder rank logit.

- [ ] **Step 1: Update `nativeInitModel` in `llama-bridge.cpp`**
  Add `jboolean isReranker` parameter.
  When `isReranker` is false (Qwen causal LLM):
  `ctx_params.embeddings = false;`
  When `isReranker` is true (BGE Reranker cross-encoder):
  `ctx_params.embeddings = true;`
  `ctx_params.pooling_type = LLAMA_POOLING_TYPE_RANK;`
- [ ] **Step 2: Update `nativeScore` in `llama-bridge.cpp`**
  Format single concatenated cross-encoder prompt: query + doc with separator token or standard format.
  Decode sequence and retrieve rank logit from `embd[0]`.
  Transform raw logit to probability via sigmoid: `1.0f / (1.0f + std::exp(-raw_logit))`.
- [ ] **Step 3: Update `LlamaBridge.kt` JNI declarations**
  Update `nativeInitModel` signature and expose `initModel(path: String, nThreads: Int, ctxSize: Int, isReranker: Boolean)`.
- [ ] **Step 4: Commit**
  `git commit -m "fix(ai): implement proper cross-encoder rank pooling and causal mode in llama-bridge"`

---

### Task 2: ChatML Prompt Formatting in QuestionReasoner

**Files:**
- Modify: `android/app/src/main/java/com/jobaut/app/bot/QuestionReasoner.kt`

- [ ] **Step 1: Wrap LLM prompts in Qwen ChatML template in `QuestionReasoner.kt`**
  Format prompt with `<|im_start|>system\n...<|im_end|>\n<|im_start|>user\n...<|im_end|>\n<|im_start|>assistant\n`.
- [ ] **Step 2: Clean ChatML stop tokens in `parseLlmResponse`**
  Strip `<|im_end|>`, `<|endoftext|>`, and markdown ticks.
- [ ] **Step 3: Commit**
  `git commit -m "fix(bot): format question reasoning prompts with ChatML for Qwen2.5"`

---

### Task 3: Job Verification & Target Matching Fallback

**Files:**
- Modify: `android/app/src/main/java/com/jobaut/app/bot/JobVerifier.kt`
- Modify: `android/app/src/test/java/com/jobaut/app/bot/JobVerifierTest.kt`

- [ ] **Step 1: Refine `JobVerifier.shouldApply`**
  If `rerankerCtxPtr != 0L`, score job using `scoreRelevance`.
  If score >= 0.40f, accept.
  If reranker score is borderline or reranker context is 0, fall back to target title match.
- [ ] **Step 2: Add test cases to `JobVerifierTest.kt`**
  Verify keyword fallback when score is neutral/uninitialized.
- [ ] **Step 3: Commit**
  `git commit -m "fix(bot): improve job verification scoring and fallback handling"`

---

### Task 4: Jobstreet Search Navigation & "New to You" Tab Parity

**Files:**
- Modify: `android/app/src/main/java/com/jobaut/app/bot/BotEngine.kt`

- [ ] **Step 1: Add Home Screen search bar interaction in `BotEngine.kt`**
  Detect search bar ("continue your job search", "search jobs"), click and enter target title keyword.
- [ ] **Step 2: Add "New to you" tab selection in search results**
  When in job feed/results, detect "New to you" tab and tap it if not yet selected.
- [ ] **Step 3: Pass `isReranker` flag during `initModels` in `BotEngine.kt`**
  `qwenCtxPtr = LlamaBridge.initModel(qwenPath, nThreads = 4, ctxSize = 1024, isReranker = false)`
  `rerankerCtxPtr = LlamaBridge.initModel(rerankerPath, nThreads = 2, ctxSize = 512, isReranker = true)`
- [ ] **Step 4: Commit**
  `git commit -m "fix(automation): add search navigation and new-to-you tab handling in BotEngine"`
