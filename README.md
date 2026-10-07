# Local Android Jobstreet Autonomous Application Agent

An entirely **local-first, on-device autonomous job application bot** engineered specifically for Android devices (ranging from 8 GB RAM flagships down to 2–4 GB RAM entry-level / "potato" smartphones) without using external cloud APIs or subscriptions. 

---

## 🖥️ Web Dashboard Quick Start

You can manage configurations, inspect training data & learned memory, and trigger `start.sh` with live logs directly from your browser:

```bash
# Launch the dashboard
python run_dashboard.py
```
Open **`http://localhost:5000`** in Chrome / Android browser (or `http://<your-phone-ip>:5000` from your PC/laptop).

- **🚀 Control Room**: Start/stop the bot, provide ADB wireless debugging port, and watch live console output streaming via SSE.
- **⚙️ Configs**: View and edit `job_search.json` and `profile.json` with syntax check and automatic backups.
- **🧠 Dataset & Memory**: Review `training_dataset.jsonl`, teach the bot answers for `unhandled_questions.json`, and search `learned_memory.json`.
- **📋 History & Logs**: Check applied jobs (`applied_tracker.csv`) and tail `llama_server.log`. 

---

## 🌟 Vision & Mission

### Vision
To democratize and streamline the job search process by providing job seekers with a completely private, offline, autonomous AI assistant that lives directly on their mobile device—eliminating repetitive application fatigue while safeguarding sensitive personal career data.

### Mission
* **Zero-Cloud Privacy:** Execute all decision-making, form-filling, and intelligence locally on-device without leaking resumes or personal records to external cloud providers.
* **Universal Hardware Accessibility:** Support low-end hardware constraints (2 GB – 4 GB RAM) via ultra-efficient quantized Small Language Models (SLMs) such as **Qwen2.5-0.5B**, **FunctionGemma-270M**, or **Qwen2.5-1.5B** with memory footprints under 500 MB.
* **Deterministic Reliability:** Combine rule-based profile matching (<1 ms latency, 0 MB overhead) with on-device AI fallbacks for nuanced questions, ensuring 100% truthful, consistent answers.
* **Strict Platform Integrity:** Restrict applications strictly to verified in-app **Quick Apply** workflows on Jobstreet (SEEK), automatically escaping external advertiser redirects and tracking every submitted job locally.

---

## 🏗️ System Architecture & Decoupled Model Pipeline

```
                    ┌──────────────────────────────────────────┐
                    │          Android Mobile Device           │
                    │       (Redmi Note 14 Pro 5G / Any)       │
                    └─────────────────────┬────────────────────┘
                                          │
            ┌─────────────────────────────┴─────────────────────────────┐
            ▼                                                           ▼
┌───────────────────────────────┐                       ┌───────────────────────────────┐
│     Termux Automation Core    │                       │       Target Application      │
│                               │                       │                               │
│  • start.sh                   │  Local ADB / Shell    │  • Jobstreet by SEEK (Android)│
│  • jobstreet_bot.py           │──────────────────────▶│  • Full-Screen Native UI      │
│  • ScreenMap Component Engine │   (Taps, Swipes,      │  • In-App Application Wizard  │
│  • Form Filler (Scroll & Read)│    Window Dumps)      │                               │
└───────┬───────────────────────┘                       └───────────────────────────────┘
        │
        ├──▶ Port 8080: Operations Model (Qwen2.5-0.5B-Instruct fine-tuned)
        │    • UI Navigation & Element Tool Calls
        │    • Screening Q&A Reasoning (Profile-grounded, zero hallucinations)
        │
        └──▶ Port 8081: Scoring Model (BGE Reranker Base Q8_0 Cross-Encoder)
             • Candidate Profile ↔ Job Description cross-encoder match
             • Sigmoid probability scoring (0–100%)
             • Inspects complete job details via auto-scroll (skills, requirements, eligibility)
```

### 🧠 Model Responsibilities

1. **Operations & UI Agent (Port 8080 - `Qwen2.5-0.5B-Instruct.Q8_0.gguf`)**:
   - Fine-tuned on `fine_tuning_dataset.jsonl` via `Train_Qwen2.5_0.5B_JobAut.ipynb`.
   - Dedicated strictly to:
     - **Tool Calling**: Autonomous navigation actions (`tap_element`, `select_dropdown_option`, `key_back`).
     - **Screening Q&A**: Resolving application form questions truthfully using candidate facts from `profile.json`.

2. **Job Fit & Scoring Engine (Port 8081 - `bge-reranker-base-q8_0.gguf`)**:
   - A dedicated **Cross-Encoder Reranker** running on `llama-server --reranking`.
   - Scores candidate skills & experience against job text into an exact probability (0–100%).
   - Before scoring, `form_filler.py` automatically performs a downward scroll to capture the **full job requirements, responsibilities, and eligibility criteria** below the fold before scrolling back up to apply.

---

## 🧩 Core Components & Functions

### 1. Master Launcher (`start.sh`)
* **Process Orchestration:** Checks if `llama-server` is listening on port 8080; if inactive, automatically boots it in the background with configured model weights.
* **Wireless ADB Auto-Reconnection:** Verifies device connection state; prompts for the current wireless debugging port if Wi-Fi reset.
* **Zero-Animation Acceleration:** Automatically disables Android window, transition, and animator scales via ADB (`settings put global ... 0`) to accelerate UI rendering and completely prevent `could not get idle state` timeouts.
* **Orientation Locking:** Disables accelerometer rotation and locks display to portrait mode (`settings put system user_rotation 0`).

---

### 2. Autonomous Agent Engine (`jobstreet_bot.py`)

#### A. Application Lifecycle & Foreground Verification
* `launch_jobstreet()`: Launches `com.jobstreet.jobstreet` into the foreground using Android OS intents (`am start --activity-brought-to-front` and `monkey`).
* `is_jobstreet_active(screen)`: Verifies that Jobstreet is visibly active on screen before any action is executed, completely avoiding unintended taps on the home launcher or background apps.
* `detect_screen_state(screen)`: Classifies the active screen into verified states:
  * `NOT_IN_JOBSTREET`
  * `POPUP_OBSTACLE`
  * `HOME_SCREEN`
  * `SEARCH_FORM`
  * `SEARCH_RESULTS`
  * `JOB_DETAILS`
  * `APPLICATION_FLOW`
  * `SELECTION_SUB_PAGE`
  * `EXTERNAL_REDIRECT_SCREEN`

#### B. Structured UI Component Mapper (`ScreenMap` Framework)
* Replaces brittle coordinate guessing with an object-oriented view tree parser:
  * `UIElement`: Wraps nodes with extracted bounding boxes, clean text labels, resource IDs, and boolean states (`is_checked`, `is_clickable`).
  * `find_button(name)`: Case-insensitive button locator.
  * `find_seek_button()`: Dynamically matches the pink search button whether it reads `"SEEK"`, `"SEEK 750 Jobs"`, or `"SEEK 2 Jobs"`.
  * `find_top_right_done()`: Locates the sub-page confirmation button with strict coordinate validation ($X > 750$) to ensure it never confuses `"Done"` with the top-left back arrow ($X = 91$).
  * `find_alert_dialog()`: Detects blocking modal dialogs such as *"Required field"*, *"Answers required for all questions"*, and *"Discard application?"*.

#### C. Intelligent Dual-Tier Decision Engine
* `match_rule_first(question_text)`: Tier 1 zero-cost evaluation matching against `profile.json`:
  * **English Level:** Strictly selects the lowest tier (*"Limited proficiency"*, *Basic*).
  * **Relocation & Travel:** Strictly answers *"No"* to protect candidate location preferences (Remote / hometown only).
  * **Salary:** Selects `₱25K` monthly basic salary.
  * **Notice Period:** Selects `Less than 2 weeks` / `Immediately`.
  * **Experience:** Accurately specifies `0` / `None` for cold calling, sales, or appointment setting roles.
  * **Education:** Supplies `Bachelor of Science in Information Technology` from College (Graduated 2026).
* `query_local_llm(question, options)`: Tier 2 on-device SLM inference via `llama-server` on localhost for arbitrary employer prompts.

#### D. Sub-Page & Form Wizard Handlers
* `handle_selection_subpage(screen)`:
  * Resolves multi-select screens with checkboxes.
  * Auto-selects lowest English proficiency options while unchecking proficient tiers.
  * Enforces pre-flight validation: verifies at least one option is checked before allowing `Done` to be tapped.
* `handle_application_step(screen)`:
  * Manages multi-step wizards (Step 1 of 4, Step 2 of 4, etc.).
  * **In-Form Scroll Sweep:** Detects hidden questions below the fold when *"Answers required for all questions"* alerts occur, taps *OK*, scrolls down, and fills the newly revealed fields.
  * **Review & Submit Loop:** Automatically performs vertical swipes on Step 4 until the pink *"Submit application"* button is revealed, taps it, and dismisses post-submit notification modals.

#### E. Strict In-App Gatekeeping
* `process_single_job(current_title)`:
  * Validates the action button: **strictly requires in-app "Quick apply"**.
  * If a job only has an *"Apply"* button that redirects to an external company site (`61549.jpg`), it logs an alert bubble, records the job in history, and immediately escapes back to search results.

---

### 3. Persistent Memory, Tracking & Dataset Logging

| Component | File | Purpose |
| :--- | :--- | :--- |
| **Learned Memory** | `learned_memory.json` | Caches answers to newly encountered questions so future jobs recall them in 0 ms without querying the LLM. |
| **Applied Tracker (CSV)** | `applied_tracker.csv` | Formatted spreadsheet logging Company Name, Job Title, Applied Timestamp, and Status. |
| **Applied Tracker (JSON)** | `applied_tracker.json` | Machine-readable application logs for automated analytics. |
| **Training Dataset** | `training_dataset.jsonl` | Standard ChatML/OpenAI instruction-tuning dataset capturing live Q&A interactions for future model fine-tuning. |
| **History Deduplication** | `applied_jobs.txt` | Persistent record of visited jobs to prevent duplicate clicks across sessions. |

---

## 🛠️ Configuration Files

### `profile.json` (Candidate Source of Truth)
Contains verified biographical, educational, and preference data:
```json
{
  "personal": {
    "full_name": "name",
    "email": "email@gmail.com",
    "phone": "0909090909",
    "city": "bulacan",
    "province": "Pangasinan",
    "english_proficiency": "Limited proficiency"
  },
  "education": {
    "has_bachelors_degree": "No",
    "degree_title": "Bachelor of Science in Information Technology",
    "school": "University / College",
    "graduation_year": "2026"
  },
  "work_preferences": {
    "authorized_to_work": "Yes",
    "requires_sponsorship": "No",
    "willing_to_relocate": "No",
    "available_to_travel": "No",
    "notice_period": "Less than 2 weeks",
    "expected_monthly_salary": "₱25K"
  }
}
```

### `job_search.json` (Search & Execution Controls)
```json
{
  "search_keyword": "office staff",
  "location": "Remote",
  "min_salary_php": 15000,
  "negative_keywords": ["unpaid", "commission only"],
  "auto_submit": true,
  "max_applications": 20
}
```

---

## 🚀 Getting Started & Execution

### Prerequisites
1. **Termux** (installed via F-Droid).
2. Packages: `pkg install android-tools python git -y`.
3. Compiled `llama.cpp` binary in `../llama.cpp/build/bin/llama-server`.
4. Quantized model file (`functiongemma-270m-it-UD-Q5_K_XL.gguf` or `qwen2.5-0.5b-instruct-q6_k.gguf`).
5. **Wireless Debugging** enabled in Android Developer Options.

### Xiaomi / HyperOS Device Settings
* **Developer options:** Enable **USB debugging (Security settings)**.
* **Termux App info:** Set Battery saver to **No restrictions**, toggle **Autostart** ON, and enable **Display pop-up windows while running in the background**.

### Running the System
From inside the `~/jobaut` directory:

```bash
./start.sh
```

---

## 📊 Live HUD Terminal Action Bubbles

While Jobstreet is running in full screen, Termux renders real-time color-coded HUD bubbles:

```text
┌─ 🤖 INITIALIZE [18:00:00] ────────────────────────┐
│ Jobstreet Autonomous Bot Online                   │
│ Target Role: Developer                            │
│ Candidate: Candidate Name                         │
└───────────────────────────────────────────────────┘

┌─ 🎯 NEW JOB [18:00:15] ───────────────────────────┐
│ Title: Start your VA career NOW!                  │
│ Progress: 0/20 applied                            │
└───────────────────────────────────────────────────┘

┌─ ⚡ QUICK APPLY [18:00:18] ────────────────────────┐
│ Applying to: 'Start your VA career...'            │
│ Company: 'Space Virtual Support'                  │
└───────────────────────────────────────────────────┘

┌─ 🧠 AI REASONING [18:00:25] ──────────────────────┐
│ Question : Are you available to travel for this...│
│ Selected : 'No'                                   │
│ Engine   : Profile Rule                           │
└───────────────────────────────────────────────────┘

┌─ 🎉 SUBMITTED [18:00:35] ─────────────────────────┐
│ Application sent successfully!                    │
└───────────────────────────────────────────────────┘

┌─ 📊 APPLICATION TRACKED [18:00:36] ───────────────┐
│ Company : Space Virtual Support                   │
│ Job     : Start your VA career NOW!               │
│ Time    : 2026-10-06 18:00:36                     │
│ Saved to: applied_tracker.csv                     │
└───────────────────────────────────────────────────┘
```

---

## 📜 License

This project is licensed under the [MIT License](LICENSE) - see the `LICENSE` file for details.

