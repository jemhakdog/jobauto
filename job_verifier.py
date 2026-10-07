import re
import json
import urllib.request
from config import PROFILE, SEARCH_CFG, DEFAULT_LLM_URL
from ui_logger import print_bubble, C_YELLOW, C_GREEN, C_RED, C_MAGENTA, C_CYAN

DISQUALIFYING_ROLES = [
    r"\bsenior\b",
    r"\bsr\.?\b",
    r"\blead\b",
    r"\bprincipal\b",
    r"\bdirector\b",
    r"\barchitect\b",
    r"\bhead of\b",
    r"\bexecutive\b"
]

DISQUALIFYING_EXP = [
    r"\b[3-9]\+?\s*(years?|yrs?)\b",
    r"\b1[0-9]\+?\s*(years?|yrs?)\b",
    r"\bminimum\s+of\s+[3-9]\s+years\b",
    r"\bat\s+least\s+[3-9]\s+years\b"
]

RELOCATION_TERMS = [
    r"\bmust\s+relocate\b",
    r"\brelocation\s+required\b",
    r"\bwilling\s+to\s+relocate\b"
]

def fast_filter_job(title: str, text: str) -> tuple[bool, str]:
    """
    Fast pre-check to eliminate obvious mismatches before querying the model.
    Checks for senior titles, heavy multi-year experience requirements, and relocation.
    """
    combined = f"{title} {text}".lower()
    title_lower = title.lower()

    # 1. Senior role in title
    for pat in DISQUALIFYING_ROLES:
        if re.search(pat, title_lower):
            return True, f"Role requires senior/lead level ({pat.replace(r'\b', '')})"

    # 2. Multi-year experience requirements (3+ years)
    for pat in DISQUALIFYING_EXP:
        match = re.search(pat, combined)
        if match:
            # Verify it's an experience requirement, not e.g. "company has 5 years history"
            surrounding = combined[max(0, match.start() - 30):min(len(combined), match.end() + 30)]
            if any(k in surrounding for k in ["experience", "exp", "background", "required", "qualifi"]):
                return True, f"Experience requirement too high ({match.group(0)})"

    # 3. Mandatory relocation
    for pat in RELOCATION_TERMS:
        if re.search(pat, combined):
            return True, "Mandatory relocation required"

    return False, ""

def build_verification_prompt(job_title: str, job_text: str, profile: dict) -> str:
    """Builds the prompt instructing the model to verify job fit."""
    skills = profile.get("experience_and_skills", {}).get("technical_skills", [])
    skills_str = ", ".join(skills[:15]) if skills else "Python, FastAPI, HTML, CSS, JavaScript, Git"
    
    clean_text = job_text.replace("\n", " ").strip()
    if len(clean_text) > 1200:
        clean_text = clean_text[:1200] + "..."

    return f"""You are a strict, objective Job Fit Verifier.

Candidate Profile:
- Current Status: BSIT College Student (not yet graduated, no completed degree).
- Formal Experience: 0-1 years (Entry-Level / Junior / Intern).
- Core Skills: {skills_str}
- Location Constraints: Remote only (cannot travel or relocate).

Target Job:
- Title: {job_title}
- Details: {clean_text}

Task:
Evaluate whether this job is a BALANCED match for this candidate:
- MATCH (true): If this is an entry-level, junior, intern, or general software/web/python/data/office role that aligns with candidate skills, or requires 0-1 year experience.
- NO MATCH (false): If it requires senior-level competence (3+ years), specialized incompatible domains (e.g., C++ embedded, sales commission, nurse, driver), or mandatory physical on-site presence requiring relocation.

Respond ONLY with valid JSON in this exact structure:
{{
  "match": true or false,
  "score": integer between 0 and 100,
  "reason": "1 concise sentence explanation"
}}
JSON Response:"""

def parse_llm_verification_response(content: str) -> tuple[bool, int, str]:
    """Parses JSON verification result from model output."""
    if not content:
        return True, 50, "Empty model response, using neutral default"

    cleaned = content.strip()
    if "```json" in cleaned:
        cleaned = cleaned.split("```json", 1)[1].split("```", 1)[0].strip()
    elif "```" in cleaned:
        cleaned = cleaned.split("```", 1)[1].split("```", 1)[0].strip()

    try:
        data = json.loads(cleaned)
        is_match = bool(data.get("match", False))
        score = int(data.get("score", 50 if is_match else 20))
        reason = str(data.get("reason", "Parsed from model evaluation"))
        return is_match, score, reason
    except Exception:
        # Fallback keyword parsing if model didn't return valid JSON
        lower = cleaned.lower()
        if '"match": true' in lower or 'match: true' in lower or 'match: yes' in lower:
            return True, 70, "Model matched job criteria"
        elif '"match": false' in lower or 'match: false' in lower or 'match: no' in lower:
            return False, 30, "Model rejected job criteria"
        return True, 50, f"Unstructured response: {cleaned[:60]}"

def verify_job_with_llm(job_title: str, job_text: str, profile: dict = None, url: str = None) -> tuple[bool, int, str]:
    """Queries local LLaMA/Qwen endpoint for verification."""
    target_profile = profile if profile is not None else PROFILE
    target_url = url or SEARCH_CFG.get("llm_endpoint", DEFAULT_LLM_URL)
    prompt = build_verification_prompt(job_title, job_text, target_profile)

    payload = {
        "messages": [
            {"role": "system", "content": "You are a concise, accurate job qualification verifier. Output pure JSON."},
            {"role": "user", "content": prompt}
        ],
        "temperature": 0.1,
        "max_tokens": 120
    }

    try:
        req = urllib.request.Request(
            target_url,
            data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json"}
        )
        with urllib.request.urlopen(req, timeout=25) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            msg = data["choices"][0]["message"]
            content = msg.get("content") or msg.get("reasoning_content", "")
            is_match, score, reason = parse_llm_verification_response(content)
            return is_match, score, reason
    except Exception as e:
        print_bubble("⚠️", "VERIFIER LLM UNAVAILABLE", [f"Endpoint: {target_url[:25]}", f"Err: {str(e)[:30]}"], color=C_YELLOW)
        # Default to optimistic pass on LLM network error so bot doesn't get stuck
        return True, 50, f"LLM offline fallback: {str(e)[:40]}"

def verify_job_with_reranker(job_title: str, job_text: str, profile: dict = None, url: str = None) -> tuple[bool, int, str]:
    """Scores candidate-job match using dedicated BGE cross-encoder reranker endpoint."""
    target_profile = profile if profile is not None else PROFILE
    target_url = url or SEARCH_CFG.get("reranker_endpoint", "http://127.0.0.1:8081/rerank")

    skills = target_profile.get("experience_and_skills", {}).get("technical_skills", [])
    skills_str = ", ".join(skills[:12]) if skills else "Python, FastAPI, JavaScript, HTML, CSS"
    query = f"Candidate Profile: Junior/Entry-level BSIT college student (0-1 yrs exp). Skills: {skills_str}. Remote only."

    clean_text = job_text.replace("\n", " ").strip()
    if len(clean_text) > 800:
        clean_text = clean_text[:800]
    document = f"Job Title: {job_title}. Details: {clean_text}"

    payload = {
        "query": query,
        "documents": [document]
    }

    try:
        req = urllib.request.Request(
            target_url,
            data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json"}
        )
        with urllib.request.urlopen(req, timeout=15) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            results = data.get("results", [])
            if results:
                # Raw logit from cross-encoder: convert via sigmoid into percentage 0-100
                raw_score = float(results[0].get("relevance_score", 0.0))
                import math
                prob = 1.0 / (1.0 + math.exp(-raw_score))
                score_pct = int(prob * 100)
                is_match = score_pct >= 40
                reason = f"Reranker logit: {raw_score:.2f} (Prob: {score_pct}%)"
                return is_match, score_pct, reason
    except Exception as e:
        print_bubble("⚠️", "RERANKER UNAVAILABLE", [f"Endpoint: {target_url[:25]}", f"Err: {str(e)[:30]}"], color=C_YELLOW)

    return True, 50, "Reranker offline fallback"

def verify_job_with_huggingface(job_title: str, job_text: str, profile: dict = None, model_name: str = None) -> tuple[bool, int, str]:
    """
    Hugging Face engine hook. Supports local huggingface transformers pipeline or zero-shot classification.
    Falls back to LLM if transformers is not installed or model is not loaded.
    """
    try:
        import transformers  # noqa: F401
        from transformers import pipeline
        model = model_name or "facebook/bart-large-mnli"
        classifier = pipeline("zero-shot-classification", model=model)
        labels = ["job matches candidate entry-level skills and remote", "job requires senior experience or incompatible skills"]
        sequence = f"Title: {job_title}. Description: {job_text[:500]}"
        res = classifier(sequence, candidate_labels=labels)
        top_label = res["labels"][0]
        score = int(res["scores"][0] * 100)
        is_match = "matches" in top_label
        return is_match, score, f"Hugging Face ({model}): {top_label}"
    except Exception as e:
        print_bubble("ℹ️", "HF ENGINE HOOK", [f"HF pipeline unavailable ({str(e)[:30]})", "Falling back to local LLM"], color=C_CYAN)
        return verify_job_with_llm(job_title, job_text, profile=profile)

def download_hf_model(repo_id: str, local_dir: str = "./models"):
    """Convenience utility to download Hugging Face models/GGUFs."""
    try:
        from huggingface_hub import snapshot_download
        print_bubble("📥", "DOWNLOADING HF MODEL", [f"Repo: {repo_id}", f"Target: {local_dir}"], color=C_MAGENTA)
        path = snapshot_download(repo_id=repo_id, local_dir=local_dir)
        print_bubble("✅", "DOWNLOAD COMPLETE", [f"Saved to: {path}"], color=C_GREEN)
        return path
    except Exception as e:
        print_bubble("❌", "DOWNLOAD FAILED", [str(e)[:60]], color=C_RED)
        return None

def verify_job_match(job_title: str, job_text: str, profile: dict = None, config: dict = None) -> tuple[bool, str, int]:
    """
    Main job verification entrypoint.
    Returns: (is_match: bool, reason: str, score: int)
    """
    cfg = config if config is not None else SEARCH_CFG
    prof = profile if profile is not None else PROFILE
    v_cfg = cfg.get("job_verification", {})

    # Check if verification is enabled
    if not v_cfg.get("enabled", True):
        return True, "Job verification is disabled in config", 100

    min_score = v_cfg.get("min_score", 50)
    engine = v_cfg.get("engine", "reranker").lower()

    # Step 1: Fast filter pre-check
    disqualified, filter_reason = fast_filter_job(job_title, job_text)
    if disqualified:
        print_bubble("🚫", "FAST FILTER REJECT", [
            f"Job: '{job_title[:25]}'",
            f"Reason: {filter_reason}"
        ], color=C_YELLOW)
        return False, f"Fast filter: {filter_reason}", 0

    # Step 2: Verification Engine
    if engine == "reranker":
        is_match, score, reason = verify_job_with_reranker(job_title, job_text, profile=prof)
    elif engine == "huggingface":
        hf_model = v_cfg.get("hf_model_name", "")
        is_match, score, reason = verify_job_with_huggingface(job_title, job_text, profile=prof, model_name=hf_model)
    else:
        is_match, score, reason = verify_job_with_llm(job_title, job_text, profile=prof)

    passed = is_match and (score >= min_score)
    color = C_GREEN if passed else C_RED
    icon = "✅" if passed else "❌"

    print_bubble(icon, "VERIFICATION RESULT", [
        f"Title : '{job_title[:30]}'",
        f"Match : {'YES' if passed else 'NO'} (Score: {score}%)",
        f"Reason: {reason[:40]}"
    ], color=color)

    return passed, reason, score
