import time
from device_controller import tap, key_back, scroll_down, scroll_up, adb
from screen_parser import ScreenMap
from storage_manager import check_memory, save_memory_entry, log_training_example, record_successful_application
from question_reasoner import (
    extract_subpage_question,
    match_rule_first,
    query_llm_for_choice,
    is_job_disallowed
)
from job_verifier import verify_job_match
from ui_logger import print_bubble, C_CYAN, C_YELLOW, C_BLUE, C_MAGENTA, C_GREEN, C_RED

def handle_selection_subpage(screen):
    print_bubble("📋", "SUB-PAGE ENGINE", ["Answering multi-choice sub-page..."], color=C_CYAN)

    alert_type, alert_btn = screen.find_alert_dialog()
    if alert_type == "REQUIRED_FIELD" and alert_btn:
        tap(alert_btn.coords[0], alert_btn.coords[1], desc="Dismiss Alert")
        time.sleep(1.2)
        screen = ScreenMap()

    options = screen.find_options()
    if not options:
        print_bubble("⚠️", "NO OPTIONS", ["No selectable options found on subpage."], color=C_YELLOW)
        key_back("Exit empty subpage")
        return False

    opt_labels = [o.label for o in options]
    question_title = extract_subpage_question(screen)
    print_bubble("❓", "QUESTION", [f"Prompt: {question_title[:40]}"], color=C_BLUE)

    selected_option = None
    selection_source = "None"

    # --- TIER 1: Memory Lookup ---
    cached_ans = check_memory(question_title)
    if cached_ans:
        for o in options:
            if cached_ans.lower() == o.label.lower() or (len(cached_ans) > 2 and cached_ans.lower() in o.label.lower()):
                selected_option = o
                selection_source = "Memory"
                break

    # --- Special Case: English Level ---
    if not selected_option and "english" in screen.all_text.lower():
        selection_source = "English Rule"
        for o in options:
            if "limited" in o.label.lower():
                selected_option = o
                break
            elif any(k in o.label.lower() for k in ["proficient", "fluent", "professional"]) and o.is_checked:
                tap(o.coords[0], o.coords[1], desc=f"Uncheck: {o.label}")
                time.sleep(0.5)

    # --- TIER 2: Deterministic Rule Match ---
    if not selected_option:
        rule_ans = match_rule_first(question_title) or match_rule_first(screen.all_text)
        if rule_ans:
            for o in options:
                if rule_ans.lower() == o.label.lower() or (len(rule_ans) > 2 and rule_ans.lower() in o.label.lower()):
                    selected_option = o
                    selection_source = "Profile Rule"
                    break

    # --- TIER 3: Local Qwen2.5-0.5B LLM ---
    if not selected_option:
        print_bubble("🧠", "LLM REASONING", ["Consulting Qwen2.5-0.5B for optimal choice..."], color=C_MAGENTA)
        llm_choice = query_llm_for_choice(question_title, opt_labels)
        if llm_choice:
            for o in options:
                if llm_choice.lower() == o.label.lower() or (len(llm_choice) > 2 and llm_choice.lower() in o.label.lower()):
                    selected_option = o
                    selection_source = "Qwen2.5-0.5B"
                    break

    # --- Fallback: Safe Default ---
    if not selected_option:
        for fallback_val in ["no", "no experience", "less than 1 year", "none"]:
            for o in options:
                if o.label.strip().lower() == fallback_val:
                    selected_option = o
                    selection_source = "Fallback Rule"
                    break
            if selected_option:
                break

    if not selected_option and options:
        selected_option = options[0]
        selection_source = "First Available Option"

    # Tap the selected choice
    if selected_option:
        print_bubble("🎯", "CHOICE MADE", [
            f"Selected: '{selected_option.label}'",
            f"Source  : {selection_source}"
        ], color=C_GREEN)
        tap(selected_option.coords[0], selected_option.coords[1], desc=f"Option: '{selected_option.label}'")
        time.sleep(0.8)

        # Self-improve: persist to learned memory and dataset
        save_memory_entry(question_title, selected_option.label)
        log_training_example(question_title, opt_labels, selected_option.label, source=selection_source)

    # Finalize / return to form
    fresh_screen = ScreenMap()
    done_btn = fresh_screen.find_top_right_done()
    if done_btn:
        print_bubble("💾", "SUB-PAGE DONE", [f"Tapping 'Done' at ({done_btn.coords[0]}, {done_btn.coords[1]})"], color=C_BLUE)
        tap(done_btn.coords[0], done_btn.coords[1], desc="Done Button")
        time.sleep(1.5)
        return True
    else:
        print_bubble("🔙", "SUB-PAGE RETURN", ["No Done button found. Navigating back with choice saved..."], color=C_BLUE)
        key_back("Return with selection saved")
        time.sleep(1.5)
        return True

def handle_application_step(screen):
    all_text = screen.all_text
    is_review_step = ("review and submit" in all_text or "step " in all_text and "submit" in all_text)

    # 1. Unanswered questions alert
    alert_type, alert_btn = screen.find_alert_dialog()
    if alert_type == "UNANSWERED_QUESTIONS_BELOW" and alert_btn:
        print_bubble("🧠", "REFLECTION & RECOVERY", [
            "Detected hidden unanswered questions!",
            "1. Tapping 'OK' on alert.",
            "2. Scrolling down to reveal hidden questions...",
            "3. Saving pattern to persistent memory."
        ], color=C_YELLOW)
        tap(alert_btn.coords[0], alert_btn.coords[1], desc="Dismiss 'Answers required' alert")
        time.sleep(1.2)
        save_memory_entry("employer_questions_requires_scroll", "true")
        adb("input swipe 540 1400 540 600 350")
        time.sleep(1.5)
        fresh_screen = ScreenMap()
        for e in fresh_screen.elements:
            if "select answer" in e.label.lower() or "please make a selection" in e.label.lower():
                tap(e.coords[0], e.coords[1], desc="Hidden Selector")
                time.sleep(1.5)
                sub = ScreenMap()
                handle_selection_subpage(sub)
                return "ADVANCED"
        return "WAIT"

    # 2. Review & Submit Screen
    if is_review_step:
        print_bubble("📝", "REVIEW SCREEN", ["Locating 'Submit application' button..."], color=C_MAGENTA)
        for _ in range(6):
            submit_btn = screen.find_button("submit application")
            if submit_btn:
                print_bubble("🚀", "SUBMIT FOUND", ["Tapping 'Submit application'..."], color=C_GREEN)
                tap(submit_btn.coords[0], submit_btn.coords[1], desc="Submit Application Button")
                print_bubble("🎉", "SUBMITTED", ["Application sent successfully!"], color=C_GREEN)
                time.sleep(3.5)
                fresh = ScreenMap()
                ml = fresh.find_button("maybe later")
                if ml:
                    tap(ml.coords[0], ml.coords[1], desc="Maybe Later")
                key_back("Return to Search Results")
                return "SUBMITTED"

            adb("input swipe 540 1500 540 450 300")
            time.sleep(1.5)
            screen = ScreenMap()
            if not screen.valid:
                break
        return "WAIT"

    # 3. Scan & fill visible unanswered fields
    def get_unfilled(scr):
        triggers = ["select answer", "please make a selection", "choose an option"]
        for e in scr.elements:
            lbl = e.label.lower()
            if any(t in lbl for t in triggers) and e.coords:
                return e
        return None

    unfilled = get_unfilled(screen)
    if unfilled:
        print_bubble("📋", "DROPDOWN TARGET", [f"Opening: '{unfilled.label[:25]}'"], color=C_CYAN)
        tap(unfilled.coords[0], unfilled.coords[1], desc="Selector")
        time.sleep(1.5)
        sub = ScreenMap()
        handle_selection_subpage(sub)
        return "ADVANCED"

    # 4. Sweep below the fold if page has questions before advancing
    continue_btn = screen.find_button("continue")
    if continue_btn:
        has_questions = any("?" in e.label for e in screen.elements) or "employer questions" in all_text
        if has_questions:
            adb("input swipe 540 1400 540 700 300")
            time.sleep(1.0)
            scrolled = ScreenMap()
            hidden_unfilled = get_unfilled(scrolled)
            if hidden_unfilled:
                print_bubble("🔍", "HIDDEN QUESTION", [f"Found: '{hidden_unfilled.label[:25]}'"], color=C_YELLOW)
                tap(hidden_unfilled.coords[0], hidden_unfilled.coords[1], desc="Revealed Selector")
                time.sleep(1.5)
                sub = ScreenMap()
                handle_selection_subpage(sub)
                return "ADVANCED"
            continue_btn = scrolled.find_button("continue") or continue_btn

        print_bubble("⏩", "STEP ADVANCE", ["Tapping 'Continue'"], color=C_BLUE)
        tap(continue_btn.coords[0], continue_btn.coords[1], desc="Continue Button")
        time.sleep(2.5)
        return "ADVANCED"

    scroll_down()
    return "WAIT"

def process_single_job(current_title="Job"):
    screen = ScreenMap()
    if not screen.valid:
        return False

    company_name = "Company"
    for e in screen.elements[:8]:
        if e.label and e.label != current_title and 2 < len(e.label) < 35:
            if not any(k in e.label.lower() for k in ["full time", "part time", "posted", "share", "apply", "save"]):
                company_name = e.label
                break

    # Collect full job text across the description (skills, requirements, eligibility)
    full_job_text_parts = [screen.all_text]
    quick_apply_btn = screen.find_button("quick apply")

    # Scroll down once to read requirements & eligibility
    scroll_down()
    time.sleep(0.6)
    screen_scrolled = ScreenMap()
    if screen_scrolled.valid and screen_scrolled.all_text:
        full_job_text_parts.append(screen_scrolled.all_text)
        if not quick_apply_btn:
            quick_apply_btn = screen_scrolled.find_button("quick apply")

    # Scroll back up so Quick Apply is visible and top state is restored
    scroll_up()
    time.sleep(0.6)
    screen = ScreenMap()
    if not quick_apply_btn and screen.valid:
        quick_apply_btn = screen.find_button("quick apply")

    combined_job_text = " ".join(dict.fromkeys(full_job_text_parts))

    # Filter out disallowed jobs
    matched_neg = is_job_disallowed(current_title) or is_job_disallowed(combined_job_text)
    if matched_neg:
        print_bubble("🚫", "SKIP UNWANTED", [f"Matched filter: '{matched_neg}'", "Escaping..."], color=C_YELLOW)
        key_back("Exit Unwanted Job")
        return False

    # Verify job matches candidate skills, requirements, and constraints
    is_match, match_reason, match_score = verify_job_match(current_title, combined_job_text)
    if not is_match:
        print_bubble("🚫", "JOB MISMATCH", [
            f"Title : '{current_title[:28]}'",
            f"Reason: {match_reason[:35]}",
            f"Score : {match_score}%",
            "Escaping..."
        ], color=C_YELLOW)
        key_back("Exit Mismatched Job")
        return False

    if not quick_apply_btn and screen.valid:
        quick_apply_btn = screen.find_button("quick apply")

    if not quick_apply_btn:
        print_bubble("🚫", "SKIP EXTERNAL", ["Not a 'Quick apply' job. Escaping..."], color=C_RED)
        key_back("Exit External Job")
        return False

    print_bubble("⚡", "QUICK APPLY", [f"Applying to: '{current_title[:25]}'", f"Company: '{company_name}'"], color=C_GREEN)
    tap(quick_apply_btn.coords[0], quick_apply_btn.coords[1], desc="Quick Apply Button")
    time.sleep(2.5)

    for _ in range(25):
        s = ScreenMap()
        if not s.valid:
            break

        alert_type, alert_btn = s.find_alert_dialog()
        if alert_type == "DISCARD_PROMPT" and alert_btn:
            tap(alert_btn.coords[0], alert_btn.coords[1], desc="Cancel Discard")
            continue
        elif alert_type == "REQUIRED_FIELD" and alert_btn:
            tap(alert_btn.coords[0], alert_btn.coords[1], desc="OK on Warning")
            continue

        if s.is_selection_subpage():
            handle_selection_subpage(s)
            continue
        elif "step " in s.all_text or s.find_button("continue") or s.find_button("submit application"):
            res = handle_application_step(s)
            if res == "SUBMITTED":
                record_successful_application(current_title, company_name)
                return True

        time.sleep(1)

    key_back("Exit Unfinished Flow")
    return False
