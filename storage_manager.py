import os
import json
from datetime import datetime
from config import MEMORY_FILE, DATASET_FILE, UNHANDLED_FILE, TRACKER_CSV_FILE, APPLIED_HISTORY_FILE, PROFILE
from ui_logger import print_bubble, C_GREEN

def check_memory(key):
    try:
        with open(MEMORY_FILE, "r", encoding="utf-8") as f:
            return json.load(f).get(key)
    except Exception:
        return None

def save_memory_entry(key, val):
    try:
        mem = {}
        if os.path.exists(MEMORY_FILE):
            with open(MEMORY_FILE, "r", encoding="utf-8") as f:
                mem = json.load(f)
        mem[key] = val
        with open(MEMORY_FILE, "w", encoding="utf-8") as f:
            json.dump(mem, f, indent=2)
    except Exception:
        pass

def log_training_example(question, options, answer, source="Qwen-1.5B"):
    try:
        entry = {
            "timestamp": datetime.now().isoformat(),
            "messages": [
                {
                    "role": "system",
                    "content": "You are an automated job application screening assistant. Using the candidate profile, select the most accurate option or provide a concise factual answer."
                },
                {
                    "role": "user",
                    "content": f"Candidate Profile:\n{json.dumps(PROFILE, indent=2)}\n\nQuestion: {question}\nOptions: {options}"
                },
                {
                    "role": "assistant",
                    "content": str(answer)
                }
            ],
            "metadata": {
                "source_engine": source,
                "screen_type": "DROPDOWN"
            }
        }
        with open(DATASET_FILE, "a", encoding="utf-8") as f:
            f.write(json.dumps(entry) + "\n")
    except Exception:
        pass

def record_unhandled_question(question_text):
    try:
        q_list = []
        if os.path.exists(UNHANDLED_FILE):
            with open(UNHANDLED_FILE, "r", encoding="utf-8") as f:
                q_list = json.load(f)
        if question_text not in q_list:
            q_list.append(question_text)
            with open(UNHANDLED_FILE, "w", encoding="utf-8") as f:
                json.dump(q_list, f, indent=2)
    except Exception:
        pass

def record_successful_application(job_title, company_name="Company"):
    timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    clean_title = job_title.strip()
    clean_company = company_name.strip()

    csv_header = not os.path.exists(TRACKER_CSV_FILE)
    with open(TRACKER_CSV_FILE, "a", encoding="utf-8") as f:
        if csv_header:
            f.write("Company Name,Job Title,Applied At,Status\n")
        f.write(f'"{clean_company}","{clean_title}","{timestamp}","Submitted"\n')

    print_bubble("📊", "APPLICATION TRACKED", [
        f"Company : {clean_company[:30]}",
        f"Job     : {clean_title[:30]}",
        f"Time    : {timestamp}",
        f"Saved to: {TRACKER_CSV_FILE}"
    ], color=C_GREEN)

def load_applied_history():
    try:
        with open(APPLIED_HISTORY_FILE, "r", encoding="utf-8") as f:
            return set(line.strip().lower() for line in f if line.strip())
    except Exception:
        return set()

def save_applied_job(title):
    try:
        with open(APPLIED_HISTORY_FILE, "a", encoding="utf-8") as f:
            f.write(title.strip().lower() + "\n")
    except Exception:
        pass
