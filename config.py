import json

def load_json(path, default=None):
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return default if default is not None else {}

PROFILE = load_json("profile.json", {})
SEARCH_CFG = load_json("job_search.json", {})

JOBSTREET_PKG = "com.jobstreet.jobstreet"
APPLIED_HISTORY_FILE = "applied_jobs.txt"
TRACKER_CSV_FILE = "applied_tracker.csv"
MEMORY_FILE = "learned_memory.json"
DATASET_FILE = "training_dataset.jsonl"
UNHANDLED_FILE = "unhandled_questions.json"
DEFAULT_LLM_URL = SEARCH_CFG.get("llm_endpoint", "http://127.0.0.1:8080/v1/chat/completions")
