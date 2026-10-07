import re
import time
import xml.etree.ElementTree as ET
from device_controller import adb

def parse_bounds(bounds_str):
    m = re.findall(r'\[(\d+),(\d+)\]', bounds_str)
    if m:
        x1, y1 = int(m[0][0]), int(m[0][1])
        x2, y2 = int(m[1][0]), int(m[1][1])
        return (x1 + x2) // 2, (y1 + y2) // 2
    return None

class UIElement:
    def __init__(self, node):
        self.raw = node
        self.text = (node.attrib.get('text') or '').strip()
        self.desc = (node.attrib.get('content-desc') or '').strip()
        self.cls = (node.attrib.get('class') or '').strip()
        self.bounds_str = node.attrib.get('bounds', '')
        self.coords = parse_bounds(self.bounds_str)
        self.is_checked = node.attrib.get('checked') == 'true'

    @property
    def label(self):
        return self.text or self.desc

class ScreenMap:
    def __init__(self):
        self.valid = False
        self.elements = []
        self.all_text = ""
        
        xml_data = ""
        for attempt in range(3):
            dump_res = adb("uiautomator dump --compressed /data/local/tmp/window_dump.xml")
            if "could not get idle state" in dump_res or "ERROR" in dump_res:
                time.sleep(0.3)
                continue
            xml_data = adb("cat /data/local/tmp/window_dump.xml")
            if "<hierarchy" in xml_data:
                self.valid = True
                break
            time.sleep(0.2)

        if self.valid:
            try:
                root = ET.fromstring(xml_data)
                self.elements = [UIElement(n) for n in root.iter()]
                self.all_text = " ".join([e.label for e in self.elements if e.label]).lower()
            except Exception:
                self.valid = False

    def find_button(self, name):
        name_lower = name.lower()
        for e in self.elements:
            if e.coords and e.label.lower() == name_lower:
                return e
        return None

    def find_top_right_done(self):
        for e in self.elements:
            if e.coords and e.coords[0] > 750 and e.label.lower() == "done":
                return e
        return None

    def is_selection_subpage(self):
        if self.find_top_right_done():
            return True
        options = self.find_options()
        has_question_or_radio = any(
            any(k in e.cls for k in ['RadioButton', 'CheckBox']) or
            ('?' in e.label and e.coords and e.coords[1] < 700)
            for e in self.elements
        )
        has_step = "step " in self.all_text and ("of " in self.all_text or "continue" in self.all_text)
        return (len(options) >= 2 and has_question_or_radio and not has_step)

    def find_seek_button(self):
        for e in self.elements:
            if e.coords and "seek" in e.label.lower():
                return e
        return None

    def find_keyword_input(self):
        # 1. Look for active EditText in search area
        for e in self.elements:
            if e.coords and "edittext" in e.cls.lower() and e.coords[1] < 700:
                return e
        # 2. Look for search box placeholder/label
        for e in self.elements:
            lbl = e.label.lower()
            if ("describe what you're looking for" in lbl or "job title, keyword" in lbl) and e.coords and e.coords[1] < 700:
                return e
        return None

    def find_clear_all_button(self):
        for e in self.elements:
            if e.coords and "clear all" in e.label.lower():
                return e
        return None

    def find_new_to_you_tab(self):
        for e in self.elements:
            lbl = e.label.lower()
            if "new to you" in lbl and e.coords and e.coords[1] < 600:
                return e
        return None

    def get_search_results_query(self):
        # Look for the header TextView above "Search Results" or near the top
        for e in self.elements:
            if e.coords and e.coords[1] < 240 and e.label:
                lbl = e.label.strip()
                if lbl.lower() not in ["search results", "navigate up", "filter"]:
                    return lbl
        return ""

    def find_options(self):
        options = []
        for e in self.elements:
            if not e.coords or e.coords[1] < 280 or e.coords[1] > 2050:
                continue
            if e.label and e.label.lower() not in ["done", "cancel", "back", "save"]:
                if any(k in e.cls for k in ['CheckBox', 'RadioButton', 'TextView', 'ViewGroup']):
                    if 1 < len(e.label) < 60:
                        options.append(e)
        return options

    def find_alert_dialog(self):
        for e in self.elements:
            lbl = e.label.lower()
            if "answers required for all questions" in lbl or "required for all questions" in lbl:
                return ("UNANSWERED_QUESTIONS_BELOW", self.find_button("ok"))
            if "required field" in lbl or "please review and try again" in lbl:
                return ("REQUIRED_FIELD", self.find_button("ok"))
            if "discard application" in lbl:
                return ("DISCARD_PROMPT", self.find_button("cancel"))
        return (None, None)

def is_jobstreet_active(screen):
    all_txt = screen.all_text
    markers = [
        "hi jem carlo", "continue your job search", "search results",
        "describe what you're looking for", "seek", "clear all",
        "quick apply", "step 1 of", "step 2 of", "step 3 of", "step 4 of",
        "review and submit", "choose documents", "answer employer questions",
        "recommended", "saved searches", "last search", "done", "cancel", "apply"
    ]
    return any(m in all_txt for m in markers)

def detect_screen_state(screen):
    all_text = screen.all_text
    if screen.is_selection_subpage():
        return "SELECTION_SUB_PAGE"
    if "step " in all_text and ("of " in all_text or "continue" in all_text or "submit" in all_text):
        return "APPLICATION_FLOW"
    if "quick apply" in all_text or ("full time" in all_text and "skills and credentials" in all_text):
        return "JOB_DETAILS"
    if "search results" in all_text or ("all " in all_text and "jobs" in all_text and "seek" not in all_text):
        return "SEARCH_RESULTS"
    if "describe what you're looking for" in all_text or "clear all" in all_text:
        return "SEARCH_FORM"
    if "continue your job search" in all_text or "hi jem carlo" in all_text or "recommended" in all_text:
        return "HOME_SCREEN"
    return "UNKNOWN"
