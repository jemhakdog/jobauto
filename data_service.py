import csv
import json
import os
import shutil
from typing import Any, Dict, List, Optional, Tuple


class DataService:
    """Provides safe reading and writing of configs, dataset, memory, and logs."""

    ALLOWED_CONFIGS = {
        "job_search": "job_search.json",
        "profile": "profile.json",
    }

    def __init__(self, workspace_dir: Optional[str] = None):
        self.workspace_dir = workspace_dir or os.path.dirname(os.path.abspath(__file__))

    def _resolve_path(self, filename: str) -> str:
        return os.path.join(self.workspace_dir, filename)

    def get_config(self, name: str) -> Dict[str, Any]:
        if name not in self.ALLOWED_CONFIGS:
            return {}
        path = self._resolve_path(self.ALLOWED_CONFIGS[name])
        if not os.path.exists(path):
            return {}
        try:
            with open(path, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            return {}

    def save_config(self, name: str, data: Dict[str, Any]) -> Tuple[bool, str]:
        if name not in self.ALLOWED_CONFIGS:
            return False, f"Invalid config name '{name}'"

        path = self._resolve_path(self.ALLOWED_CONFIGS[name])
        bak_path = path + ".bak"
        tmp_path = path + ".tmp"

        try:
            # Validate JSON serializable
            json_str = json.dumps(data, indent=2, ensure_ascii=False)

            # Write to tmp file first
            with open(tmp_path, "w", encoding="utf-8") as f:
                f.write(json_str)

            # Backup original if exists
            if os.path.exists(path):
                shutil.copyfile(path, bak_path)

            # Atomic replace
            os.replace(tmp_path, path)
            return True, ""
        except Exception as e:
            if os.path.exists(tmp_path):
                os.remove(tmp_path)
            return False, str(e)

    def get_training_data(self, limit: int = 50, offset: int = 0) -> Dict[str, Any]:
        path = self._resolve_path("training_dataset.jsonl")
        if not os.path.exists(path):
            return {"items": [], "total": 0}

        items = []
        total = 0
        try:
            with open(path, "r", encoding="utf-8") as f:
                for line in f:
                    line = line.strip()
                    if not line:
                        continue
                    total += 1
                    if total > offset and len(items) < limit:
                        try:
                            items.append(json.loads(line))
                        except Exception:
                            items.append({"raw": line})
            return {"items": items, "total": total}
        except Exception:
            return {"items": [], "total": 0}

    def get_unhandled_questions(self) -> List[str]:
        path = self._resolve_path("unhandled_questions.json")
        if not os.path.exists(path):
            return []
        try:
            with open(path, "r", encoding="utf-8") as f:
                data = json.load(f)
                return data if isinstance(data, list) else []
        except Exception:
            return []

    def get_learned_memory(self, search: Optional[str] = None) -> Dict[str, str]:
        path = self._resolve_path("learned_memory.json")
        if not os.path.exists(path):
            return {}
        try:
            with open(path, "r", encoding="utf-8") as f:
                data = json.load(f)
                if not isinstance(data, dict):
                    return {}
                if search:
                    q = search.lower()
                    return {k: v for k, v in data.items() if q in k.lower() or q in str(v).lower()}
                return data
        except Exception:
            return {}

    def learn_question(self, question: str, answer: str) -> bool:
        memory_path = self._resolve_path("learned_memory.json")
        unhandled_path = self._resolve_path("unhandled_questions.json")

        memory = self.get_learned_memory()
        memory[question] = answer

        # Write memory
        try:
            with open(memory_path + ".tmp", "w", encoding="utf-8") as f:
                json.dump(memory, f, indent=2, ensure_ascii=False)
            os.replace(memory_path + ".tmp", memory_path)
        except Exception:
            return False

        # Remove from unhandled
        unhandled = self.get_unhandled_questions()
        unhandled = [q for q in unhandled if q != question]
        try:
            with open(unhandled_path + ".tmp", "w", encoding="utf-8") as f:
                json.dump(unhandled, f, indent=2, ensure_ascii=False)
            os.replace(unhandled_path + ".tmp", unhandled_path)
        except Exception:
            pass

        return True

    def get_applied_jobs(self, limit: int = 100) -> List[Dict[str, str]]:
        path = self._resolve_path("applied_tracker.csv")
        if not os.path.exists(path):
            return []
        jobs = []
        try:
            with open(path, "r", encoding="utf-8") as f:
                reader = csv.DictReader(f)
                for row in reader:
                    jobs.append(row)
            return jobs[-limit:][::-1]  # Most recent first
        except Exception:
            return []

    def get_llama_logs(self, tail_lines: int = 100) -> str:
        path = self._resolve_path("llama_server.log")
        if not os.path.exists(path):
            return "(No llama_server.log found)"
        try:
            with open(path, "r", encoding="utf-8", errors="ignore") as f:
                lines = f.readlines()
                return "".join(lines[-tail_lines:])
        except Exception as e:
            return f"(Error reading log: {e})"
