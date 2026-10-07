# JobStreet Auto-Applier Web Dashboard Specification

## 1. Overview
A lightweight, mobile-responsive local web dashboard for the JobStreet Auto-Applier bot running inside Termux on Android. The dashboard allows full customization of configuration files (`job_search.json`, `profile.json`), live monitoring of logs and bot processes, triggering `./start.sh` execution with interactive inputs (like ADB port), and browsing data (training dataset, learned memory, unhandled questions, and applied job tracker).

## 2. Technology Stack
- **Backend**: Python 3 with Flask (`flask`), using standard threading/subprocess mechanisms.
- **Frontend**: Clean single-page application (HTML5, Tailwind CSS via CDN or standalone embedded CSS, Vanilla JavaScript, Server-Sent Events / SSE for live streaming).
- **Target Host/Port**: `http://0.0.0.0:5000` (accessible from localhost or local Wi-Fi network).

## 3. Core Architecture & Endpoints

### 3.1 Process Management & Bot Launcher
- `POST /api/bot/start`: Starts `./start.sh` as a managed subprocess. Accepts optional `{ "adb_port": 12345 }` to automate wireless debugging connection without hanging on stdin.
- `POST /api/bot/stop`: Terminates the running bot process gracefully (SIGTERM/SIGKILL).
- `GET /api/bot/status`: Returns current bot execution status (`running`, `stopped`), LLaMA server status (checking `http://127.0.0.1:8080/health`), and ADB status (`adb devices`).
- `GET /api/bot/logs/stream`: Server-Sent Events (SSE) streaming real-time stdout/stderr from the running bot process.

### 3.2 Configuration Management
- `GET /api/config/:name`: Fetches JSON contents of `job_search.json` or `profile.json`.
- `POST /api/config/:name`: Validates and saves updated JSON config with automatic backup (`.bak`).

### 3.3 Data & Memory Inspection
- `GET /api/data/training`: Reads and parses `training_dataset.jsonl` (supports pagination / recent entries and total count).
- `GET /api/data/unhandled`: Returns list of questions in `unhandled_questions.json`.
- `POST /api/data/memory/learn`: Adds or updates a question-answer pair in `learned_memory.json` and removes it from `unhandled_questions.json`.
- `GET /api/data/memory`: Returns key-value pairs from `learned_memory.json` with search filter.
- `GET /api/data/applied`: Parses `applied_tracker.csv` and `applied_jobs.txt` into structured records for tabular viewing.
- `GET /api/data/llama-logs`: Returns recent tail of `llama_server.log`.

## 4. UI Components & Layout
1. **Header / Navbar**:
   - Status indicators: Bot status, LLaMA Server status, ADB connection status.
   - Navigation tabs: "Control Room", "Configs", "Memory & Training", "Application History".
2. **Tab 1: Control Room**:
   - Primary action buttons: Start Bot / Stop Bot.
   - Port input field for Wireless Debugging if ADB is not connected.
   - Terminal window with dark theme, auto-scroll toggle, and clear button.
3. **Tab 2: Config Manager**:
   - Toggle between Structured Forms (sliders, inputs, keyword tags) and Raw JSON editor with syntax validation.
   - Quick presets and save button with toast notifications.
4. **Tab 3: Memory & Training**:
   - **Unhandled Questions Queue**: List of pending questions with an inline answer field and "Learn" button to save directly into `learned_memory.json`.
   - **Learned Memory**: Searchable table of question-answer pairs with delete/edit ability.
   - **Training Dataset Viewer**: Accordion list of training prompts and completions from `training_dataset.jsonl`.
5. **Tab 4: Application History**:
   - Data table of applied jobs loaded from `applied_tracker.csv`, showing date, job title, company, and status.

## 5. Security & Safety
- Binds to localhost / LAN with safe file path resolution restricted strictly to the workspace directory.
- Atomic writes for configuration files with backup creation to prevent corruption.
