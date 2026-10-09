# Architectural Design: JobAut All-in-One Native Android APK

**Date:** 2026-10-09  
**Status:** Approved by User  
**Target Platform:** Android (arm64-v8a, API 26+)  
**CI/CD Pipeline:** GitHub Actions  

---

## 1. Executive Summary

This document specifies the architecture for migrating **JobAut** from a Python/Termux/ADB script toolchain to a fully self-contained, high-performance native Android application packaged into a single APK.

### Key Objectives
1. **Zero External Dependencies:** Eliminate requirements for Termux, Python runtime, external `llama-server` process, and ADB Wireless Debugging pairing.
2. **Native Automation:** Use an Android `AccessibilityService` for sub-10ms UI node inspection, direct node clicking, and instant text setting.
3. **On-Device AI Engine:** Embed `llama.cpp` via Android NDK C++ shared libraries (`libllama.so`) with JNI bindings for local inference.
4. **All-in-One Packaging:** Bundle the LLM (`Qwen2.5-0.5B-Instruct`) and classification/reranking model (`bge-reranker-base`) directly into the APK assets via GitHub Actions CI/CD.
5. **Modern Dashboard:** Replicate and modernize the Flask web dashboard as a native **Jetpack Compose** interface with live log streaming and background service controls.

---

## 2. System Architecture

```
┌────────────────────────────────────────────────────────────────────────┐
│                        JobAut Android APK                              │
├────────────────────────────────────────────────────────────────────────┤
│  [UI Layer: Jetpack Compose (Material 3)]                             │
│   • Dashboard / Status Card                                            │
│   • Real-Time Activity Log Viewer                                      │
│   • Profile & Criteria Configuration Editor                            │
│   • Floating Screen Bubble (Optional live overlay on Jobstreet)        │
├───────────────────────────────────┬────────────────────────────────────┤
│  [Bot Automation Engine]          │  [JobAutForegroundService]         │
│   • State Machine (FSM)           │   • Sticky Notification            │
│   • Question Reasoner             │   • Process Keep-Alive             │
│   • Job Verification & DB Logger  │   • Emergency Stop Action          │
├───────────────────────────────────┴────────────────────────────────────┤
│  [JobstreetAccessibilityService]                                       │
│   • Inspects AccessibilityNodeInfo tree (<10ms)                        │
│   • Performs ACTION_CLICK & ACTION_SET_TEXT                            │
│   • Dispatches gestures (scrolls/swipes) & GLOBAL_ACTION_BACK          │
├────────────────────────────────────────────────────────────────────────┤
│  [LlamaBridge (Kotlin JNI)]                                            │
│   • nativeInitModel(path, threads, ctx) -> Native context pointer      │
│   • nativeGenerate(contextPtr, prompt, maxTokens) -> Response string   │
│   • nativeScore(contextPtr, query, doc) -> Re-ranking score float      │
│   • nativeFree(contextPtr)                                             │
├────────────────────────────────────────────────────────────────────────┤
│  [Native C++ Layer (NDK)]                                              │
│   • libllama.so + GGML (ARM NEON accelerated)                          │
├────────────────────────────────────────────────────────────────────────┤
│  [Bundled Assets & Storage]                                            │
│   • assets/models/qwen2.5-0.5b-instruct.gguf                           │
│   • assets/models/bge-reranker-base.gguf                               │
│   • Room SQLite Database (applied_jobs, learned_questions, logs)       │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Detailed Component Specifications

### 3.1 Native NDK & C++ Layer (`libllama.so` & JNI)

* **Build Tool:** CMake 3.22+ via Android NDK r26+.
* **Target ABI:** `arm64-v8a` with compiler flags `-O3 -march=armv8-a+simd+fp16`.
* **C++ Source Files:**
  - `app/src/main/cpp/CMakeLists.txt`
  - `app/src/main/cpp/llama-bridge.cpp`: Exposes JNI methods for model initialization, prompt inference, and cross-encoder score calculation.
* **Memory Management:**
  - Models mapped directly via memory-mapped files (`mmap`) to conserve system RAM.
  - Separate context handles for:
    1. **Operations LLM Context:** ~1024 context tokens for answering job screening questions.
    2. **Reranker Context:** ~512 tokens for scoring job title & snippet relevance.

### 3.2 Android Automation (`JobstreetAccessibilityService`)

* **Declaration:** Declared in `AndroidManifest.xml` requiring `BIND_ACCESSIBILITY_SERVICE`.
* **Configuration (`res/xml/accessibility_service_config.xml`):**
  - Target packages: `com.jobstreet.jobstreet`, `com.seek.jobstreet`.
  - Flags: `FLAG_REPORT_VIEW_IDS`, `FLAG_RETRIEVE_INTERACTIVE_WINDOWS`.
  - Capabilities: `canRetrieveWindowContent`, `canPerformGestures`.
* **Automation Primitives:**
  - `findNodeByTextOrDesc(text: String): AccessibilityNodeInfo?`
  - `clickNode(node: AccessibilityNodeInfo): Boolean` -> executes `ACTION_CLICK` on the target or nearest clickable parent.
  - `setText(node: AccessibilityNodeInfo, text: String): Boolean` -> executes `ACTION_SET_TEXT` with arguments bundle.
  - `scroll(direction: Direction): Boolean` -> invokes `ACTION_SCROLL_FORWARD` or `dispatchGesture`.
  - `navigateBack(): Boolean` -> executes `performGlobalAction(GLOBAL_ACTION_BACK)`.

### 3.3 Bot Engine State Machine

The automation logic is implemented in a Kotlin coroutine state machine running inside `JobAutForegroundService`:

```
           ┌──────────────────────┐
           │     IDLE / OFF       │
           └──────────┬───────────┘
                      │ User taps "START BOT"
                      ▼
           ┌──────────────────────┐
           │   LAUNCH_JOBSTREET   │◄──────────────┐
           └──────────┬───────────┘               │
                      │ App detected              │
                      ▼                           │
       ┌──────────► SCAN_FEED ◄──────────┐        │
       │              │                  │        │
       │              ▼                  │        │
       │      EVALUATE_JOB (Reranker)    │        │
       │       /              \          │        │
[Below Threshold]        [Matches Criteria]       │
       │                        ▼                 │
       │                 OPEN_JOB_DETAILS         │
       │                        │                 │
       │                        ▼                 │
       │                  APPLY_FORM              │
       │                        │                 │
       │                        ▼                 │
       │               ANSWER_QUESTIONS (Qwen)    │
       │                        │                 │
       │                        ▼                 │
       │                SUBMIT_APPLICATION       │
       │                        │                 │
       └────────────────────────┴─────────────────┘
```

1. **Scan Feed:** Scans the active window for job list items.
2. **Evaluate Job:** Checks blacklist keywords and calls `LlamaBridge.score()` to verify fit.
3. **Apply & Form Filling:** If quick apply is available, clicks through the flow. Detects form questions, fetches answers from user profile or generates answers via `LlamaBridge.generate()`.
4. **Verification & Logging:** Verifies confirmation dialogs and writes records into Room SQLite.

### 3.4 Data Persistence (Room Database)

Replaces CSV and JSON flat files with SQLite managed by Android Room:
* `AppliedJobEntity`: ID, title, company, job_id, apply_date, salary, status.
* `LearnedQuestionEntity`: Question hash/text, answer, confidence, source (user/ai).
* `ConfigPreferences`: User personal profile, job filters, threshold scores, LLM parameters (stored in `DataStore`).

### 3.5 Jetpack Compose Dashboard

* **Overview & Status:**
  - Real-time bot status indicator (Running, Idle, Paused).
  - Accessibility permission check banner with 1-click shortcut to system settings.
  - Quick action buttons: "Start Bot", "Stop Bot", "Launch Jobstreet".
* **Live Monitor:**
  - Daily applied count and skip counters.
  - Live log console with color-coded tags (`ACTION`, `INPUT`, `LLM`, `SUCCESS`, `ERROR`).
* **Profile & Settings:**
  - Editable user profile fields (Contact info, work authorization, expected salary, notice period).
  - Search criteria and exclusions.

### 3.6 GitHub Actions CI/CD Pipeline

* **Workflow File:** `.github/workflows/build-apk.yml`
* **Trigger:** Manual dispatch or push to `main` branch.
* **Pipeline Steps:**
  1. **Environment Setup:** Ubuntu runner, Java 17, Android SDK & NDK r26b, CMake 3.22.
  2. **Asset Hydration:**
     - Downloads `Qwen2.5-0.5B-Instruct-Q8_0.gguf` (~530 MB) and `bge-reranker-base-q8_0.gguf` (~300 MB) from Hugging Face into `app/src/main/assets/models/`.
  3. **Build Execution:** `./gradlew assembleRelease` (or `assembleDebug`).
  4. **Artifact Upload:** Uploads `JobAut-arm64-v8a.apk` as a downloadable artifact.

---

## 4. Error Handling & Edge Cases

1. **System Accessibility Killed:** If Android stops the accessibility service due to aggressive battery management, the foreground notification alerts the user to re-enable it.
2. **Unsupported Job Application (External Webview):** If Jobstreet opens an external Chrome Custom Tab or third-party ATS, the state machine detects non-Jobstreet package and executes `navigateBack()` to avoid getting stuck.
3. **Thermal Throttling / Out of Memory:** The LLM runs with conservative thread counts (4 threads for 0.5B, 2 threads for Reranker) and memory mapping (`mmap`) to ensure low temperature and low memory footprint (<1.2 GB total).
