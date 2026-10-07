import json
import urllib.request
from config import PROFILE, SEARCH_CFG, DEFAULT_LLM_URL
from storage_manager import record_unhandled_question
from ui_logger import print_bubble, C_YELLOW

def is_job_disallowed(text):
    clean = text.lower()
    negative_list = SEARCH_CFG.get("negative_keywords", [])
    for neg in negative_list:
        if neg.lower() in clean:
            return neg
    return None

def query_llm_for_choice(question, options):
    url = SEARCH_CFG.get("llm_endpoint", DEFAULT_LLM_URL)
    prompt = f"""Candidate Verified Profile (RESUME FACTS ONLY):
- Full Name: Jem Carlo G. Austria
- Education: Currently studying BSIT at Binalatongan Community College (2024-Present). NOT graduated yet, does NOT hold a completed Bachelor's degree.
- Actual Work Experience: 0 years total formal work experience. Only 1 internship (IT Intern at Local Civil Registrar, Feb 2026 - data management, record encoding).
- Roles & Skills:
  * Customer Service: NO EXPERIENCE / 0 years.
  * Cold Calling / Sales / Outbound / Appointment Setter: NO EXPERIENCE / NO.
  * HR / Recruitment: NO EXPERIENCE / 0 years.
  * Back Office / Data Entry / IT Support: Less than 1 year (internship / student).
  * Software / Web Development: Less than 1 year (academic / personal projects: Python, FastAPI, React, Next.js).
- Work Constraints:
  * Travel: CANNOT travel (No).
  * Relocation: CANNOT relocate (No). Strictly remote or hometown (Mangatarem).
  * Notice Period: Less than 2 weeks.
  * English Proficiency: Limited proficiency.

Question: {question}
Available Options: {options}

STRICT INSTRUCTIONS:
1. NEVER hallucinate or exaggerate candidate experience. If asked about experience in roles not listed with experience (like Customer Service, Sales, Cold Calling, HR, Appointment Setting), choose "No experience", "No", or "None".
2. You MUST select and respond with ONLY the exact option from the Available Options list above.
3. Output zero commentary, zero explanation, and zero surrounding quotes.
Answer:"""

    payload = {
        "messages": [
            {"role": "system", "content": "You are a strictly factual job application assistant. You never fabricate experience and only choose from the provided options."},
            {"role": "user", "content": prompt}
        ],
        "temperature": 0.0,
        "max_tokens": 30
    }

    try:
        req = urllib.request.Request(
            url,
            data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json"}
        )
        with urllib.request.urlopen(req, timeout=8) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            answer = data["choices"][0]["message"]["content"].strip()
            answer = answer.strip('`"\'* \n')
            return answer
    except Exception as e:
        print_bubble("⚠️", "LLM OFFLINE", [f"Endpoint: {url[:30]}", f"Err: {str(e)[:30]}"], color=C_YELLOW)
        return None

def extract_subpage_question(screen):
    for e in screen.elements:
        if not e.coords or e.coords[1] > 700:
            continue
        lbl = e.label.strip()
        if len(lbl) > 15 and "?" in lbl:
            return lbl
    for e in screen.elements:
        if not e.coords or e.coords[1] > 700:
            continue
        lbl = e.label.strip()
        if any(lbl.lower().startswith(q) for q in ["how many", "do you have", "what is", "are you", "how would", "which"]):
            return lbl
    return screen.all_text[:120]

def match_rule_first(question_text):
    clean = question_text.lower()
    
    # 1. English Proficiency
    if "english" in clean and ("rate" in clean or "proficien" in clean or "skill" in clean or "level" in clean):
        return "Limited proficiency"
        
    # 2. Travel & Relocation
    if any(k in clean for k in ["travel", "travel for this role", "domestic travel", "international travel"]):
        return "No"
    if any(k in clean for k in ["relocate", "relocation", "change location", "move to", "live near"]):
        return "No"
        
    # 3. Salary & Notice
    if any(k in clean for k in ["expected monthly", "basic salary", "expected salary"]):
        return "₱25K"
    if any(k in clean for k in ["notice", "required to give"]):
        return "Less than 2 weeks"
        
    # 4. Strict Role & Experience Queries (Grounded to Resume)
    if any(k in clean for k in ["customer service", "csr", "support officer"]):
        return "No experience"
    if any(k in clean for k in ["appointment setter", "cold call", "telemarket", "telemarketing"]):
        return "No experience"
    if any(k in clean for k in ["sales environment", "sales", "outbound", "closing"]):
        return "No"
    if any(k in clean for k in ["voice", "call center", "phone call", "phone handling"]):
        return "No"
    if any(k in clean for k in ["math", "accounting", "accountant", "bookkeep", "audit", "financial"]):
        return "No"
    if any(k in clean for k in ["social media", "content creation", "tiktok", "instagram", "facebook page"]):
        return "No"
    if any(k in clean for k in ["human resources", "hr assistant", "recruitment"]):
        return "No experience"
    if any(k in clean for k in ["virtual assistant"]):
        return "No experience"
    if any(k in clean for k in ["data entry", "record", "encoder", "back office", "it support", "software dev"]):
        return "Less than 1 year"
    if any(k in clean for k in ["how many years", "years' experience", "years of experience"]):
        return "No experience"
        
    # 5. Education & Background
    if any(k in clean for k in ["bachelor", "degree", "college graduate"]):
        return "No"
    if any(k in clean for k in ["school", "university", "college"]):
        return PROFILE["education"]["school"]
    if any(k in clean for k in ["course", "major"]):
        return PROFILE["education"]["degree_title"]
    if any(k in clean for k in ["graduat", "year"]):
        return PROFILE["education"]["graduation_year"]
    
    if len(question_text.strip()) > 3:
        record_unhandled_question(question_text.strip())
    return None
