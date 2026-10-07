import json
import random
import os
from config import PROFILE

def generate_screening_qa():
    """Generates varied screening questions with strict factual answers matching the profile."""
    templates = [
        # Experience questions
        ("How many years of experience do you have in customer service?", ["No experience", "Less than 1 year", "1-2 years", "3+ years"], "No experience"),
        ("Do you have experience in cold calling or telemarketing?", ["Yes", "No"], "No"),
        ("Have you worked in an outbound sales role before?", ["Yes", "No"], "No"),
        ("How many years experience do you have as an Appointment Setter?", ["No experience", "Less than 1 year", "1 year", "2 years", "3+ years"], "No experience"),
        ("Do you have experience in human resources or recruitment?", ["Yes", "No"], "No"),
        ("How many years experience do you have in Data Entry?", ["No experience", "Less than 1 year", "1-2 years", "3+ years"], "Less than 1 year"),
        ("How much experience do you have in Back Office support?", ["No experience", "Less than 1 year", "1-2 years", "3+ years"], "Less than 1 year"),
        ("How many years of IT Support experience do you possess?", ["No experience", "Less than 1 year", "1-2 years", "3+ years"], "Less than 1 year"),
        ("Do you have experience in Software Development / Web Development?", ["Yes", "No"], "Yes"),
        ("Years of experience in Python or web programming?", ["No experience", "Less than 1 year", "1-2 years", "3+ years"], "Less than 1 year"),
        
        # Education questions
        ("Do you have a completed Bachelor's Degree?", ["Yes", "No"], "No"),
        ("Are you a college graduate?", ["Yes", "No"], "No"),
        ("What is your highest educational attainment?", ["High School", "College Undergraduate / In Progress", "Bachelor's Degree", "Master's Degree"], "College Undergraduate / In Progress"),
        ("What school or university do you currently attend?", ["Binalatongan Community College", "Other", "Pangasinan State University"], "Binalatongan Community College"),
        ("What degree or course are you taking?", ["BS Information Technology", "BS Computer Science", "BS Business Admin"], "BS Information Technology"),
        ("What is your expected graduation year?", ["2025", "2026", "2027", "Already graduated"], "2026"),

        # Work preferences and constraints
        ("Are you willing to relocate for this position?", ["Yes", "No"], "No"),
        ("Can you travel domestically or internationally for work?", ["Yes", "No"], "No"),
        ("Are you available to work on-site in Metro Manila / Cebu?", ["Yes", "No"], "No"),
        ("Do you require visa sponsorship to work?", ["Yes", "No"], "No"),
        ("Are you legally authorized to work in the Philippines?", ["Yes", "No"], "Yes"),
        ("What is your notice period / availability to start?", ["Immediately", "Less than 2 weeks", "30 days", "More than 1 month"], "Less than 2 weeks"),
        ("What is your expected monthly salary?", ["₱15,000 - ₱20,000", "₱25,000", "₱30,000 - ₱40,000", "Negotiable"], "₱25,000"),
        ("What is your English proficiency level?", ["Basic / Limited proficiency", "Conversational", "Fluent / Professional", "Native"], "Basic / Limited proficiency"),
    ]

    prefix_variations = [
        "",
        "Please confirm: ",
        "Employer Question: ",
        "Screening Question: ",
        "Mandatory requirement: "
    ]

    samples = []
    for _ in range(25): # Expanded sample set
        for q, opts, correct in templates:
            prefix = random.choice(prefix_variations)
            shuffled_opts = opts.copy()
            random.shuffle(shuffled_opts)
            
            matched_ans = next((o for o in shuffled_opts if correct.lower() in o.lower() or o.lower() in correct.lower()), correct)

            sample = {
                "task": "screening_qa",
                "messages": [
                    {
                        "role": "system",
                        "content": "You are a job application screening assistant. Rely solely on the candidate's verified profile facts. Never hallucinate experience. Respond strictly with the exact matching option."
                    },
                    {
                        "role": "user",
                        "content": f"Candidate Profile:\n{json.dumps(PROFILE, indent=2)}\n\nQuestion: {prefix}{q}\nOptions: {shuffled_opts}"
                    },
                    {
                        "role": "assistant",
                        "content": matched_ans
                    }
                ]
            }
            samples.append(sample)
    return samples

def generate_tool_calling_data():
    """Generates successful tool-calling actions covering form filling, escaping duplicates, and search feed navigation."""
    tool_definitions = [
        {"name": "tap_element", "description": "Taps an interactive element or button on the screen", "parameters": {"target": "string", "coords": "[x, y]"}},
        {"name": "select_dropdown_option", "description": "Selects a specific choice from a dropdown dialog", "parameters": {"option": "string"}},
        {"name": "key_back", "description": "Navigates back to escape from unwanted, external, or already applied jobs", "parameters": {"reason": "string"}},
        {"name": "scroll_search_feed", "description": "Scrolls down the search feed to find fresh unapplied job cards", "parameters": {}},
        {"name": "switch_feed_tab", "description": "Switches to a different search tab such as 'New to you'", "parameters": {"tab": "string"}},
        {"name": "submit_application", "description": "Finalizes and submits the job application", "parameters": {}},
        {"name": "dismiss_alert", "description": "Safely dismisses info or warning dialogs", "parameters": {"button": "string"}}
    ]

    scenarios = [
        # --- FORM PROGRESSION SCENARIOS ---
        {
            "screen_type": "JOB_DETAILS",
            "ui_state": {"title": "Junior Python Developer", "buttons": ["Save", "Quick apply", "Share"], "company": "Tech Corp"},
            "call": {"name": "tap_element", "arguments": {"target": "Quick apply", "coords": [540, 2100]}}
        },
        {
            "screen_type": "FORM_STEP",
            "ui_state": {"step": "1 of 2", "unfilled_field": "Select answer", "field_label": "Notice period", "buttons": ["Continue"]},
            "call": {"name": "tap_element", "arguments": {"target": "Select answer", "coords": [540, 850]}}
        },
        {
            "screen_type": "DROPDOWN_SUBPAGE",
            "ui_state": {"question": "What is your notice period?", "options": ["Less than 2 weeks", "1 month", "2 months"], "selected": None},
            "call": {"name": "select_dropdown_option", "arguments": {"option": "Less than 2 weeks"}}
        },
        {
            "screen_type": "DROPDOWN_SUBPAGE",
            "ui_state": {"selected": "Less than 2 weeks", "top_right_button": "Done"},
            "call": {"name": "tap_element", "arguments": {"target": "Done", "coords": [980, 150]}}
        },
        {
            "screen_type": "ALERT_DIALOG",
            "ui_state": {"alert_text": "Answers required for employer questions below", "button": "OK"},
            "call": {"name": "dismiss_alert", "arguments": {"button": "OK"}}
        },
        {
            "screen_type": "FORM_STEP",
            "ui_state": {"all_required_filled": True, "buttons": ["Continue"]},
            "call": {"name": "tap_element", "arguments": {"target": "Continue", "coords": [540, 2150]}}
        },
        {
            "screen_type": "REVIEW_STEP",
            "ui_state": {"page": "Review and submit", "buttons": ["Submit application"]},
            "call": {"name": "submit_application", "arguments": {}}
        },

        # --- ESCAPING DEAD-ENDS & DUPLICATES (PREVENTS LOOPS) ---
        {
            "screen_type": "JOB_DETAILS",
            "ui_state": {"title": "Sales Agent", "badge": "Applied 2 days ago", "buttons": ["Application submitted", "Share"]},
            "call": {"name": "key_back", "arguments": {"reason": "Job is already applied"}}
        },
        {
            "screen_type": "JOB_DETAILS",
            "ui_state": {"title": "Outbound Telemarketer", "buttons": ["Apply on company site"], "has_quick_apply": False},
            "call": {"name": "key_back", "arguments": {"reason": "External redirect, no Quick Apply"}}
        },
        {
            "screen_type": "JOB_DETAILS",
            "ui_state": {"title": "Bilingual CSR - Relocation to Cebu", "requires_relocation": True, "buttons": ["Quick apply"]},
            "call": {"name": "key_back", "arguments": {"reason": "Mismatched relocation constraint"}}
        },
        {
            "screen_type": "JOB_DETAILS",
            "ui_state": {"title": "Senior Cloud Architect (10+ Yrs Exp Required)", "min_experience": 10, "buttons": ["Quick apply"]},
            "call": {"name": "key_back", "arguments": {"reason": "Mismatched experience requirement"}}
        },

        # --- SEARCH FEED NAVIGATION ---
        {
            "screen_type": "SEARCH_RESULTS",
            "ui_state": {"active_tab": "All jobs", "available_tabs": ["All jobs", "New to you"], "cards_seen": 15},
            "call": {"name": "switch_feed_tab", "arguments": {"tab": "New to you"}}
        },
        {
            "screen_type": "SEARCH_RESULTS",
            "ui_state": {"active_tab": "New to you", "visible_jobs": ["Already applied", "Already applied", "Already applied"]},
            "call": {"name": "scroll_search_feed", "arguments": {}}
        },
        {
            "screen_type": "SEARCH_RESULTS",
            "ui_state": {"active_tab": "New to you", "all_visible_cards_processed": True},
            "call": {"name": "scroll_search_feed", "arguments": {}}
        },
        {
            "screen_type": "SEARCH_RESULTS",
            "ui_state": {"query": "customer service", "required_target_keyword": "python"},
            "call": {"name": "key_back", "arguments": {"reason": "Wrong search results query, reset search"}}
        }
    ]

    samples = []
    for _ in range(35): # 35 cycles across 15 scenarios = 525 samples
        for s in scenarios:
            prompt_content = f"Available Tools:\n{json.dumps(tool_definitions, indent=2)}\n\nCurrent Screen State:\n{json.dumps(s['ui_state'], indent=2)}\nScreen Type: {s['screen_type']}\n\nSelect the next forward-progressing action. If the job is already applied, external, or mismatched, escape immediately."
            target_call = f"<tool_call>\n{json.dumps(s['call'])}\n</tool_call>"

            sample = {
                "task": "tool_calling",
                "messages": [
                    {
                        "role": "system",
                        "content": "You are an autonomous mobile UI navigation agent. Output only valid <tool_call> blocks to execute forward progress or escape safely."
                    },
                    {
                        "role": "user",
                        "content": prompt_content
                    },
                    {
                        "role": "assistant",
                        "content": target_call
                    }
                ]
            }
            samples.append(sample)
    return samples

if __name__ == "__main__":
    qa_data = generate_screening_qa()
    tool_data = generate_tool_calling_data()
    
    total_data = qa_data + tool_data
    random.shuffle(total_data)

    output_path = "fine_tuning_dataset.jsonl"
    with open(output_path, "w", encoding="utf-8") as f:
        for item in total_data:
            f.write(json.dumps(item) + "\n")

    print(f"Generated {len(qa_data)} Screening QA samples.")
    print(f"Generated {len(tool_data)} Tool-calling & Navigation samples.")
    print(f"Total fine-tuning dataset: {len(total_data)} samples at '{output_path}'")
