# JobStreet Auto-Applier Web Dashboard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a lightweight, responsive Flask web dashboard allowing full customization of configs, live execution and terminal log streaming for `start.sh`, and inspection/management of dataset and learned memory.

**Architecture:** A modular Python backend with a `bot_manager.py` handling asynchronous subprocessing/streaming, a `dashboard_api.py` implementing Flask routes and REST endpoints, and a single responsive `templates/index.html` with vanilla JS and CSS.

**Tech Stack:** Python 3, Flask, pytest, Server-Sent Events (SSE), HTML5, CSS3, Vanilla JS.

**Spec:** [`docs/superpowers/specs/2026-10-07-dashboard-design.md`](file:///data/data/com.termux/files/home/jobaut/docs/superpowers/specs/2026-10-07-dashboard-design.md)

## Global Constraints
- Target host/port: `0.0.0.0:5000` (allowing phone browser and local network access).
- Safe file path boundaries: Restricted strictly to the project workspace directory.
- Atomic configuration saving: Writes to temporary file before replacing, preserving a `.bak` backup file.
- Non-blocking bot execution: Bot process runs in background thread/process with thread-safe log buffer.

## Review Focus
1. Starting bot when already running -> Returns 400 with descriptive error without creating duplicate processes.
2. Saving invalid JSON configuration -> Returns 400 with syntax error details and leaves original file untouched.
3. Reading missing or empty data files (e.g., empty `training_dataset.jsonl` or missing logs) -> Returns clean empty list/message rather than 500 error.
4. Handling port parameter in bot start -> Passes `PORT` environment variable or stdin so `start.sh` does not block on `read -p`.
5. Learning an unhandled question -> Atomically updates `learned_memory.json` and removes the question from `unhandled_questions.json`.

---

### Task 1: Bot Manager Subsystem
**Files:**
- Create: `bot_manager.py`
- Create: `tests/test_bot_manager.py`

**Interfaces:**
- Produces: `class BotManager`:
  - `start(script_path: str = "./start.sh", adb_port: Optional[str] = None) -> bool`
  - `stop() -> bool`
  - `is_running() -> bool`
  - `get_status() -> dict`
  - `get_logs(last_pos: int = 0) -> Tuple[str, int]`
  - `stream_logs() -> Generator[str, None, None]`

- [ ] **Step 1: Write the failing test for `bot_manager.py`**
- [ ] **Step 2: Run pytest to verify failure**
- [ ] **Step 3: Implement `BotManager` in `bot_manager.py`**
- [ ] **Step 4: Run pytest to verify all tests pass**
- [ ] **Step 5: Commit**

---

### Task 2: Config and Data Service Layer
**Files:**
- Create: `data_service.py`
- Create: `tests/test_data_service.py`

**Interfaces:**
- Produces: `class DataService`:
  - `get_config(name: str) -> dict`
  - `save_config(name: str, data: dict) -> Tuple[bool, str]`
  - `get_training_data(limit: int = 50, offset: int = 0) -> dict`
  - `get_unhandled_questions() -> list`
  - `learn_question(question: str, answer: str) -> bool`
  - `get_learned_memory(search: Optional[str] = None) -> dict`
  - `get_applied_jobs() -> list`
  - `get_llama_logs(tail_lines: int = 100) -> str`

- [ ] **Step 1: Write the failing test for `data_service.py`**
- [ ] **Step 2: Run pytest to verify failure**
- [ ] **Step 3: Implement `DataService` in `data_service.py`**
- [ ] **Step 4: Run pytest to verify all tests pass**
- [ ] **Step 5: Commit**

---

### Task 3: Flask API & Web Endpoints
**Files:**
- Create: `app.py`
- Create: `tests/test_app.py`

**Interfaces:**
- Consumes: `BotManager`, `DataService`
- Produces: Flask web server with REST & SSE endpoints:
  - `GET /` -> Serves `templates/index.html`
  - `GET /api/bot/status`
  - `POST /api/bot/start`
  - `POST /api/bot/stop`
  - `GET /api/bot/logs/stream`
  - `GET /api/config/<name>`
  - `POST /api/config/<name>`
  - `GET /api/data/training`
  - `GET /api/data/unhandled`
  - `POST /api/data/memory/learn`
  - `GET /api/data/memory`
  - `GET /api/data/applied`
  - `GET /api/data/llama-logs`

- [ ] **Step 1: Write the failing test for `app.py` API routes**
- [ ] **Step 2: Run pytest to verify failure**
- [ ] **Step 3: Implement Flask routes in `app.py`**
- [ ] **Step 4: Run pytest to verify all tests pass**
- [ ] **Step 5: Commit**

---

### Task 4: Interactive Frontend UI (HTML, CSS, JS)
**Files:**
- Create: `templates/index.html`
- Create: `static/dashboard.js`
- Create: `static/dashboard.css`

**Interfaces:**
- Consumes: All Flask API routes (`/api/*`)
- Produces: Responsive UI with:
  - Tab 1: Control Room (Live Status, Start/Stop Bot, ADB Port input, Auto-scrolling Terminal console via SSE)
  - Tab 2: Config Editor (Interactive editor for `job_search.json` and `profile.json` with syntax check)
  - Tab 3: Dataset & Memory (Unhandled questions answering interface, searchable memory table, training dataset viewer)
  - Tab 4: History & Logs (Applied jobs table and LLaMA server log viewer)

- [ ] **Step 1: Create modern CSS layout in `static/dashboard.css`**
- [ ] **Step 2: Create UI markup structure in `templates/index.html`**
- [ ] **Step 3: Implement client-side dynamic interactivity in `static/dashboard.js`**
- [ ] **Step 4: Verify UI template rendering via Flask test client**
- [ ] **Step 5: Commit**

---

### Task 5: Integration & Launcher Verification
**Files:**
- Create: `run_dashboard.py` (easy CLI launcher)
- Update: `README.md` (add dashboard instructions)

- [ ] **Step 1: Create `run_dashboard.py` launcher with host/port arguments**
- [ ] **Step 2: Run full automated test suite `pytest -v`**
- [ ] **Step 3: Verify server starts and responds to health and index requests**
- [ ] **Step 4: Update `README.md` and commit**
