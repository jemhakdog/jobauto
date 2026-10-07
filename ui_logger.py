import shutil
from datetime import datetime

# ANSI Terminal Colors
C_RESET   = "\033[0m"
C_BOLD    = "\033[1m"
C_CYAN    = "\033[36m"
C_GREEN   = "\033[32m"
C_YELLOW  = "\033[33m"
C_BLUE    = "\033[34m"
C_MAGENTA = "\033[35m"
C_RED     = "\033[31m"

def print_bubble(tag, title, lines, color=C_CYAN):
    term_width = min(shutil.get_terminal_size((48, 20)).columns, 52)
    timestamp = datetime.now().strftime("%H:%M:%S")
    header = f" {tag} {title} [{timestamp}] "
    box_width = max(term_width - 2, len(header) + 4)
    dash_count = max(box_width - len(header) - 2, 2)
    print(f"\n{color}┌─{C_BOLD}{header}{C_RESET}{color}{'─' * dash_count}┐{C_RESET}")
    for line in lines:
        wrapped = [line[i:i+box_width-4] for i in range(0, len(line), box_width-4)]
        for w in wrapped:
            padding = " " * (box_width - len(w) - 2)
            print(f"{color}│ {C_RESET}{w}{padding}{color}│{C_RESET}")
    print(f"{color}└{'─' * (box_width)}┘{C_RESET}")
