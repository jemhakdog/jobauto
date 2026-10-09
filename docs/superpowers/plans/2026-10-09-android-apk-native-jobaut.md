# JobAut Native Android APK Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a complete, self-contained native Android project (Kotlin + NDK + Jetpack Compose + AccessibilityService) that packages JobAut's dashboard, llama.cpp C++ engine, models, and automation into a single APK built via GitHub Actions.

**Architecture:** A native Android architecture utilizing an `AccessibilityService` for sub-10ms UI interaction, an NDK C++ JNI bridge (`libllama.so`) for in-memory model inference without HTTP overhead, an Android Foreground Service for background persistence, and a Jetpack Compose Material 3 dashboard.

**Tech Stack:** Kotlin, Jetpack Compose, Android NDK (C++ / CMake), Room SQLite Database, Kotlin Coroutines & StateFlow, GitHub Actions CI/CD.

**Spec:** [`docs/superpowers/specs/2026-10-09-android-apk-native-jobaut-design.md`](file:///data/data/com.termux/files/home/jobaut/docs/superpowers/specs/2026-10-09-android-apk-native-jobaut-design.md)

## Global Constraints

- **Platform Target:** Android 8.0+ (minSdk 26, targetSdk 34, compileSdk 34).
- **Architecture:** `arm64-v8a`.
- **Zero ADB Dependency:** All UI automation must execute via `AccessibilityService`.
- **In-Memory LLM:** `llama.cpp` must run inside the app process via JNI without spawning external HTTP server processes.
- **Model Bundling:** Workflows must fetch and package `Qwen2.5-0.5B-Instruct` and `bge-reranker-base` into APK `assets/models/`.

## Review Focus

1. **Accessibility Permission Revocation:** App gracefully detects when Accessibility Service is disabled and prompts the user to re-enable in Settings.
2. **External Webview Navigation:** Bot detects navigation outside `com.jobstreet.jobstreet` and presses Back to avoid getting trapped in third-party browsers.
3. **Model Memory Exhaustion:** LLM context allocation uses mmap and capped context size (1024 for Qwen, 512 for Reranker) to prevent OOM errors.
4. **Form Submit Loop Prevention:** Bot tracks current job ID and will not repeatedly submit the same application.
5. **Background Process Killing:** Service registers as a sticky Foreground Service with `startForeground()` and an ongoing notification.

---

### Task 1: Android Project Scaffolding & Gradle Build Configuration

**Files:**
- Create: `android/build.gradle.kts`
- Create: `android/settings.gradle.kts`
- Create: `android/app/build.gradle.kts`
- Create: `android/app/src/main/AndroidManifest.xml`
- Create: `android/gradle.properties`

**Interfaces:**
- Produces: Complete Android build configuration with Compose, Room, Coroutines, and NDK/CMake linkages.

- [ ] **Step 1: Create `android/settings.gradle.kts` and `android/gradle.properties`**
  Define plugin repositories (google, mavenCentral) and project name `JobAut`.
- [ ] **Step 2: Create root `android/build.gradle.kts`**
  Declare Android Application and Kotlin Android plugins (AGP 8.3+, Kotlin 1.9+).
- [ ] **Step 3: Create `android/app/build.gradle.kts`**
  Configure `defaultConfig` (minSdk 26, targetSdk 34, `ndk { abiFilters += "arm64-v8a" }`, CMake path), dependencies for Compose, Room, Navigation, Coroutines, and Lifecycle.
- [ ] **Step 4: Create `android/app/src/main/AndroidManifest.xml`**
  Declare permissions (`FOREGROUND_SERVICE`, `POST_NOTIFICATIONS`), `MainActivity`, `JobAutService`, and `JobstreetAccessibilityService`.
- [ ] **Step 5: Commit**
  `git add android/ && git commit -m "feat(android): add gradle project scaffolding and manifest"`

---

### Task 2: Native NDK Layer & llama.cpp JNI Bridge

**Files:**
- Create: `android/app/src/main/cpp/CMakeLists.txt`
- Create: `android/app/src/main/cpp/llama-bridge.cpp`
- Create: `android/app/src/main/java/com/jobaut/app/ai/LlamaBridge.kt`

**Interfaces:**
- Consumes: GGUF model paths in internal storage.
- Produces: `LlamaBridge.kt` object with JNI methods:
  - `initModel(path: String, nThreads: Int, ctxSize: Int): Long`
  - `generate(ctxPtr: Long, prompt: String, maxTokens: Int): String`
  - `scoreRelevance(ctxPtr: Long, query: String, doc: String): Float`
  - `freeModel(ctxPtr: Long)`

- [ ] **Step 1: Create `android/app/src/main/cpp/CMakeLists.txt`**
  Configure CMake 3.22+, fetch/link `llama.cpp` sources, and define `llama-bridge` shared library.
- [ ] **Step 2: Create `android/app/src/main/cpp/llama-bridge.cpp`**
  Implement JNI functions matching `com.jobaut.app.ai.LlamaBridge` package:
  `Java_com_jobaut_app_ai_LlamaBridge_nativeInitModel`, `Java_com_jobaut_app_ai_LlamaBridge_nativeGenerate`, `Java_com_jobaut_app_ai_LlamaBridge_nativeScore`, and `Java_com_jobaut_app_ai_LlamaBridge_nativeFree`.
- [ ] **Step 3: Create `android/app/src/main/java/com/jobaut/app/ai/LlamaBridge.kt`**
  Load `System.loadLibrary("llama-bridge")` and declare external functions wrapped in coroutine dispatchers (`Dispatchers.Default`).
- [ ] **Step 4: Commit**
  `git add android/app/src/main/cpp/ android/app/src/main/java/com/jobaut/app/ai/ && git commit -m "feat(ai): implement native llama.cpp NDK JNI bridge"`

---

### Task 3: Data Layer & Persistence (Room Database & DataStore)

**Files:**
- Create: `android/app/src/main/java/com/jobaut/app/data/AppliedJobEntity.kt`
- Create: `android/app/src/main/java/com/jobaut/app/data/AppliedJobDao.kt`
- Create: `android/app/src/main/java/com/jobaut/app/data/LearnedQuestionEntity.kt`
- Create: `android/app/src/main/java/com/jobaut/app/data/LearnedQuestionDao.kt`
- Create: `android/app/src/main/java/com/jobaut/app/data/AppDatabase.kt`
- Create: `android/app/src/main/java/com/jobaut/app/data/UserConfigManager.kt`

**Interfaces:**
- Produces:
  - `AppDatabase.getInstance(context)`
  - `AppliedJobDao.insert(job: AppliedJobEntity)`
  - `AppliedJobDao.isApplied(jobId: String): Boolean`
  - `LearnedQuestionDao.findAnswer(queryHash: String): String?`
  - `UserConfigManager.loadProfile(): UserProfile`

- [ ] **Step 1: Create Entities and DAOs**
  Implement `AppliedJobEntity` with fields (`id`, `jobId`, `title`, `company`, `appliedAt`, `salary`, `status`) and `LearnedQuestionEntity` (`hash`, `questionText`, `answerText`).
- [ ] **Step 2: Create `AppDatabase.kt`**
  Room database class registering `AppliedJobEntity` and `LearnedQuestionEntity`.
- [ ] **Step 3: Create `UserConfigManager.kt`**
  Manager for user profile (name, phone, email, experience, notice period, expected salary, excluded keywords).
- [ ] **Step 4: Commit**
  `git add android/app/src/main/java/com/jobaut/app/data/ && git commit -m "feat(data): implement Room database and config storage"`

---

### Task 4: Android Accessibility Service (`JobstreetAccessibilityService`)

**Files:**
- Create: `android/app/src/main/res/xml/accessibility_service_config.xml`
- Create: `android/app/src/main/java/com/jobaut/app/automation/JobstreetAccessibilityService.kt`
- Create: `android/app/src/main/java/com/jobaut/app/automation/ScreenMap.kt`

**Interfaces:**
- Produces:
  - `JobstreetAccessibilityService.instance: JobstreetAccessibilityService?`
  - `JobstreetAccessibilityService.getScreenMap(): ScreenMap`
  - `JobstreetAccessibilityService.click(node: AccessibilityNodeInfo): Boolean`
  - `JobstreetAccessibilityService.setText(node: AccessibilityNodeInfo, text: String): Boolean`
  - `JobstreetAccessibilityService.scrollDown(): Boolean`
  - `JobstreetAccessibilityService.goBack(): Boolean`

- [ ] **Step 1: Create `res/xml/accessibility_service_config.xml`**
  Set `canRetrieveWindowContent="true"`, `canPerformGestures="true"`, `accessibilityFeedbackType="feedbackGeneric"`.
- [ ] **Step 2: Create `ScreenMap.kt`**
  Traverse `rootInActiveWindow` to extract interactive nodes (text, description, bounds, clickable state).
- [ ] **Step 3: Implement `JobstreetAccessibilityService.kt`**
  Subclass `AccessibilityService`, expose static singleton instance to bot engine, implement click, set text, scroll gestures, and back navigation.
- [ ] **Step 4: Commit**
  `git add android/app/src/main/res/xml/ android/app/src/main/java/com/jobaut/app/automation/ && git commit -m "feat(automation): implement JobstreetAccessibilityService and ScreenMap"`

---

### Task 5: Bot Engine & Question Reasoner

**Files:**
- Create: `android/app/src/main/java/com/jobaut/app/bot/QuestionReasoner.kt`
- Create: `android/app/src/main/java/com/jobaut/app/bot/JobVerifier.kt`
- Create: `android/app/src/main/java/com/jobaut/app/bot/BotEngine.kt`

**Interfaces:**
- Consumes: `JobstreetAccessibilityService`, `LlamaBridge`, `AppDatabase`, `UserConfigManager`.
- Produces: `BotEngine.start()`, `BotEngine.stop()`, `BotEngine.statusFlow: StateFlow<BotStatus>`.

- [ ] **Step 1: Create `JobVerifier.kt`**
  Evaluate job title against blacklist and call `LlamaBridge.scoreRelevance` to verify candidate fit.
- [ ] **Step 2: Create `QuestionReasoner.kt`**
  Examine screening question text, check learned memory/profile, or call `LlamaBridge.generate()` to formulate concise answers.
- [ ] **Step 3: Implement `BotEngine.kt`**
  Coroutine execution loop:
  1. Detect screen (Feed vs Job Details vs Form vs Done).
  2. In Feed: Rerank and tap job.
  3. In Job: Tap "Quick Apply".
  4. In Form: Fill questions, select radio/checkboxes, tap "Next" / "Submit".
  5. In Confirmation: Record applied job in Room DB.
- [ ] **Step 4: Commit**
  `git add android/app/src/main/java/com/jobaut/app/bot/ && git commit -m "feat(bot): implement state machine bot engine and reasoning"`

---

### Task 6: Foreground Service (`JobAutService`)

**Files:**
- Create: `android/app/src/main/java/com/jobaut/app/service/JobAutService.kt`
- Create: `android/app/src/main/res/values/strings.xml`

**Interfaces:**
- Produces: Android Foreground Service that runs `BotEngine` with persistent notification channel and stop action.

- [ ] **Step 1: Implement `JobAutService.kt`**
  Create notification channel, build ongoing notification displaying live applied count, handle `ACTION_START` and `ACTION_STOP` intents.
- [ ] **Step 2: Connect `BotEngine` life cycle**
  Start `BotEngine` coroutine on `ACTION_START`, cancel on `ACTION_STOP` or service destroy.
- [ ] **Step 3: Commit**
  `git add android/app/src/main/java/com/jobaut/app/service/ android/app/src/main/res/values/ && git commit -m "feat(service): implement foreground keep-alive service"`

---

### Task 7: Jetpack Compose Dashboard UI

**Files:**
- Create: `android/app/src/main/java/com/jobaut/app/ui/MainActivity.kt`
- Create: `android/app/src/main/java/com/jobaut/app/ui/DashboardViewModel.kt`
- Create: `android/app/src/main/java/com/jobaut/app/ui/screens/DashboardScreen.kt`
- Create: `android/app/src/main/java/com/jobaut/app/ui/screens/LogScreen.kt`
- Create: `android/app/src/main/java/com/jobaut/app/ui/screens/ProfileScreen.kt`

**Interfaces:**
- Produces: Complete Jetpack Compose interface with Material 3 styling.

- [ ] **Step 1: Create `DashboardViewModel.kt`**
  Expose UI state (Bot running status, accessibility permission status, today's applied count, log stream).
- [ ] **Step 2: Create `DashboardScreen.kt`**
  Status card, Start/Stop toggle button, Launch Jobstreet button, and stats overview.
- [ ] **Step 3: Create `LogScreen.kt` and `ProfileScreen.kt`**
  Live auto-scrolling log console with colored message tags; Profile screen for editing candidate profile.
- [ ] **Step 4: Implement `MainActivity.kt`**
  Set Jetpack Compose theme, navigation bar, and permission checking.
- [ ] **Step 5: Commit**
  `git add android/app/src/main/java/com/jobaut/app/ui/ && git commit -m "feat(ui): implement Jetpack Compose dashboard and settings screens"`

---

### Task 8: GitHub Actions CI/CD Workflow & Model Packaging

**Files:**
- Create: `.github/workflows/build-apk.yml`
- Create: `android/gradlew`
- Create: `android/gradle/wrapper/gradle-wrapper.properties`

**Interfaces:**
- Produces: GitHub Actions CI pipeline that downloads the GGUF models during the build and outputs the release APK.

- [ ] **Step 1: Create `gradle-wrapper.properties` and wrapper script**
  Configure Gradle 8.5 wrapper.
- [ ] **Step 2: Create `.github/workflows/build-apk.yml`**
  Workflow configuration:
  - Checkout repository.
  - Setup Java 17.
  - Setup Android SDK & NDK r26b.
  - Download `Qwen2.5-0.5B-Instruct-Q8_0.gguf` & `bge-reranker-base-q8_0.gguf` into `android/app/src/main/assets/models/`.
  - Execute `./gradlew assembleRelease --no-daemon`.
  - Upload `android/app/build/outputs/apk/release/*.apk` as GitHub release/run artifact.
- [ ] **Step 3: Commit**
  `git add .github/ android/gradle/ && git commit -m "ci: add GitHub Actions workflow to bundle models and build APK"`
