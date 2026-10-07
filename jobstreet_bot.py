import time
from config import PROFILE, SEARCH_CFG
from ui_logger import print_bubble, C_GREEN, C_CYAN, C_YELLOW, C_RED, C_BLUE, C_MAGENTA
from device_controller import lock_portrait, launch_jobstreet, tap, type_text, key_back, scroll_down, adb
from screen_parser import ScreenMap, is_jobstreet_active, detect_screen_state
from storage_manager import (
    load_applied_history,
    save_applied_job,
    check_memory,
    save_memory_entry
)
from question_reasoner import is_job_disallowed
from form_filler import handle_selection_subpage, handle_application_step, process_single_job

def run_bot():
    print_bubble("🤖", "INITIALIZE", [
        "Jobstreet Autonomous Bot Online",
        f"Target Role: {SEARCH_CFG['search_keyword'].upper()}",
        f"Candidate: {PROFILE['personal']['full_name']}"
    ], color=C_GREEN)

    lock_portrait()
    applied_history = load_applied_history()
    print_bubble("📚", "HISTORY LOADED", [f"Tracking {len(applied_history)} applied/seen jobs."], color=C_CYAN)

    applied_count = 0
    max_apps = SEARCH_CFG.get("max_applications", 20)

    from collections import deque
    history_window = deque(maxlen=8)
    stuck_counter = 0
    switched_to_new_to_you = False

    while applied_count < max_apps:
        screen = ScreenMap()
        
        # Verify Jobstreet is in foreground
        if not screen.valid or not is_jobstreet_active(screen):
            print_bubble("⚠️ ", "APP NOT ACTIVE", ["Jobstreet is not on screen.", "Launching Jobstreet now..."], color=C_YELLOW)
            launch_jobstreet()
            time.sleep(2.0)
            continue

        alert_type, alert_btn = screen.find_alert_dialog()
        if alert_btn:
            tap(alert_btn.coords[0], alert_btn.coords[1], desc=f"Resolve Alert ({alert_type})")
            continue

        state = detect_screen_state(screen)
        print_bubble("📍", "STATE VERIFIED", [f"Current Screen: {state}"], color=C_CYAN)

        # Loop & Oscillation detection (A->B->A->B or consecutive stalls)
        state_sig = (state, screen.all_text[:80])
        history_window.append(state_sig)

        # Count occurrences in rolling window
        seen_count = sum(1 for s in history_window if s == state_sig)
        if seen_count >= 3:
            stuck_counter += 1
            print_bubble("🔁", "CYCLE DETECTED", [
                f"Screen repeated {seen_count}x in recent window",
                f"Loop Counter: {stuck_counter}/4"
            ], color=C_YELLOW)
        else:
            stuck_counter = max(0, stuck_counter - 1)

        if stuck_counter >= 4:
            print_bubble("⚠️", "BREAKING LOOP", [
                f"Oscillation/loop confirmed in state: {state}",
                "Executing escape back & scroll..."
            ], color=C_RED)
            key_back("Loop Escape")
            scroll_down()
            history_window.clear()
            if stuck_counter >= 8:
                print_bubble("🔄", "REBOOT APP", ["Hard restart of Jobstreet..."], color=C_RED)
                adb(f"am force-stop {screen_parser.JOBSTREET_PKG if hasattr(screen_parser, 'JOBSTREET_PKG') else 'com.jobstreet.jobstreet'}")
                time.sleep(1.0)
                launch_jobstreet()
                stuck_counter = 0
            continue

        if state == "SELECTION_SUB_PAGE":
            handle_selection_subpage(screen)
            continue

        elif state == "APPLICATION_FLOW":
            res = handle_application_step(screen)
            if res == "SUBMITTED":
                applied_count += 1
            continue

        elif state == "JOB_DETAILS":
            process_single_job()
            scroll_down()
            continue

        elif state == "HOME_SCREEN":
            print_bubble("🏠", "HOME SCREEN", ["Tapping search bar..."], color=C_BLUE)
            for e in screen.elements:
                if "continue your job search" in e.label.lower():
                    tap(e.coords[0], e.coords[1], desc="Search Bar")
                    time.sleep(2)
                    break
            continue

        elif state == "SEARCH_FORM":
            target_keyword = SEARCH_CFG.get("search_keyword", "").strip()
            
            # Check if keyword is already in the search input / form
            input_box = screen.find_keyword_input()
            input_text = (input_box.text if input_box else "").strip().lower()
            keyword_matches = bool(target_keyword and target_keyword.lower() in input_text)

            # Only proceed with seek if the keyword has been entered
            seek_btn = screen.find_seek_button()
            if seek_btn and keyword_matches:
                print_bubble("🚀", "SEEK FOUND", [f"Tapping '{seek_btn.label}' for '{target_keyword}'..."], color=C_GREEN)
                tap(seek_btn.coords[0], seek_btn.coords[1], desc=seek_btn.label)
                time.sleep(3.5)
                continue

            # If keyword is not set or not matching, force input into search bar
            print_bubble("🔍", "FORCE SEARCH KEYWORD", [f"Setting keyword to '{target_keyword}'"], color=C_CYAN)
            
            # Clear any existing text first if clear all is available
            clear_btn = screen.find_clear_all_button()
            if clear_btn:
                tap(clear_btn.coords[0], clear_btn.coords[1], desc="Clear all")
                time.sleep(0.5)

            if not input_box:
                input_box = screen.find_keyword_input()

            if input_box:
                tap(input_box.coords[0], input_box.coords[1], desc="Keyword Input Box")
                time.sleep(0.5)
                type_text(target_keyword, field_name="Keyword")
                adb("input keyevent 66")  # Enter/Search
                time.sleep(1.5)
                
                screen = ScreenMap()
                seek_btn = screen.find_seek_button()
                if seek_btn:
                    tap(seek_btn.coords[0], seek_btn.coords[1], desc=seek_btn.label)
                    time.sleep(3.5)
                continue

            scroll_down()
            continue

        elif state == "SEARCH_RESULTS":
            target_keyword = SEARCH_CFG.get("search_keyword", "").strip()
            active_query = screen.get_search_results_query()

            # Ensure we are actually viewing results for our target keyword
            if target_keyword and active_query and target_keyword.lower() not in active_query.lower():
                print_bubble("⚠️", "WRONG SEARCH QUERY", [
                    f"Active query on screen: '{active_query}'",
                    f"Target keyword required: '{target_keyword}'",
                    "Navigating back to search bar..."
                ], color=C_YELLOW)
                nav_up = screen.find_button("navigate up")
                if nav_up:
                    tap(nav_up.coords[0], nav_up.coords[1], desc="Navigate up")
                else:
                    key_back("Exit Incorrect Search Results")
                switched_to_new_to_you = False
                time.sleep(2.0)
                continue

            # 1. Switch to 'New to you' tab if visible and not already on it
            # In Jobstreet, tabs are 'All jobs' and 'New to you'. If 'All jobs' has the underline/active state or 'New to you' is present, tap it.
            new_to_you_tab = screen.find_new_to_you_tab()
            if new_to_you_tab and not switched_to_new_to_you:
                print_bubble("✨", "NEW TO YOU", [f"Tapping 'New to you' at ({new_to_you_tab.coords[0]}, {new_to_you_tab.coords[1]})..."], color=C_GREEN)
                tap(new_to_you_tab.coords[0], new_to_you_tab.coords[1], desc="New to you Tab")
                switched_to_new_to_you = True
                time.sleep(2.5)
                continue

            cards = []
            for e in screen.elements:
                if len(e.label) > 15 and e.coords and 400 < e.coords[1] < 1900:
                    clean = e.label.strip().lower()
                    if not any(k in clean for k in ["search results", "all jobs", "strong applicant", "new to you", "save this", "filter"]):
                        if not any(h in clean or clean in h for h in applied_history):
                            disallowed = is_job_disallowed(clean)
                            if disallowed:
                                print_bubble("🚫", "FILTERED OUT", [f"Title: '{clean[:28]}'", f"Blocked: '{disallowed}'"], color=C_YELLOW)
                                applied_history.add(clean)
                                continue
                            cards.append(e)

            if not cards:
                print_bubble("📜", "NO NEW CARDS", ["Scrolling down for next jobs..."], color=C_BLUE)
                scroll_down()
                continue

            target = cards[0]
            print_bubble("🎯", "NEW JOB", [f"Title: {target.label[:35]}", f"Progress: {applied_count}/{max_apps}"], color=C_MAGENTA)
            
            applied_history.add(target.label.strip().lower())
            save_applied_job(target.label)

            tap(target.coords[0], target.coords[1], desc=f"Open: '{target.label[:20]}'")
            time.sleep(2.5)

            if process_single_job(current_title=target.label):
                applied_count += 1
                print_bubble("✅", "COMPLETE", [f"Progress: {applied_count}/{max_apps}"], color=C_GREEN)

            scroll_down()
            continue

        else:
            time.sleep(2)

if __name__ == "__main__":
    run_bot()
