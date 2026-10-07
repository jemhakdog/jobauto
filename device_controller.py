import time
import subprocess
from ui_logger import print_bubble, C_BLUE, C_MAGENTA, C_GREEN, C_YELLOW
from config import JOBSTREET_PKG

def adb(cmd):
    try:
        proc = subprocess.run(
            f"adb shell {cmd}",
            shell=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            timeout=12
        )
        return proc.stdout.decode('utf-8', errors='ignore')
    except Exception:
        return ""

def lock_portrait():
    # Leave device orientation as set by user; do not toggle settings
    pass

def launch_jobstreet():
    print_bubble("🚀", "LAUNCH APP", ["Starting Jobstreet in full screen..."], color=C_BLUE)
    try:
        adb(f"am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p {JOBSTREET_PKG} --activity-brought-to-front")
    except Exception:
        pass
    try:
        adb(f"monkey -p {JOBSTREET_PKG} -c android.intent.category.LAUNCHER 1")
    except Exception:
        pass
    time.sleep(3.5)

def tap(x, y, desc="Screen Target"):
    print_bubble("👆", "ACTION", [f"Target: {desc}", f"Coords: ({x}, {y})"], color=C_MAGENTA)
    adb(f"input tap {x} {y}")
    time.sleep(0.5)

def type_text(text, field_name="Input"):
    print_bubble("⌨️ ", "INPUT", [f"Field: {field_name}", f"Typing: '{text}'"], color=C_GREEN)
    safe_text = str(text).replace(" ", "%s")
    adb(f"input text {safe_text}")
    time.sleep(0.5)

def key_back(reason="Navigate Back"):
    print_bubble("🔙", "BACK", [f"Reason: {reason}"], color=C_YELLOW)
    adb("input keyevent 4")
    time.sleep(0.8)

def scroll_down():
    print_bubble("📜", "SCROLL", ["Center swipe down..."], color=C_BLUE)
    adb("input swipe 540 1400 540 500 250")
    time.sleep(1.0)

def scroll_up():
    print_bubble("📜", "SCROLL UP", ["Center swipe up..."], color=C_BLUE)
    adb("input swipe 540 500 540 1400 250")
    time.sleep(1.0)

