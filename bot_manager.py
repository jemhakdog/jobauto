import os
import pty
import select
import signal
import subprocess
import threading
import time
from typing import Dict, Generator, Optional, Tuple
import urllib.request


class BotManager:
    """Manages the lifecycle, subprocess execution, status, and output stream for start.sh."""

    def __init__(self, workspace_dir: Optional[str] = None):
        self.workspace_dir = workspace_dir or os.path.dirname(os.path.abspath(__file__))
        self.process: Optional[subprocess.Popen] = None
        self._log_buffer = ""
        self._lock = threading.Lock()
        self._master_fd: Optional[int] = None
        self._reader_thread: Optional[threading.Thread] = None

    def is_running(self) -> bool:
        if self.process is None:
            return False
        ret = self.process.poll()
        return ret is None

    def get_status(self) -> Dict[str, object]:
        bot_running = self.is_running()
        llama_running = self._check_llama_health()
        adb_connected = self._check_adb_status()

        return {
            "bot_running": bot_running,
            "llama_server_running": llama_running,
            "adb_connected": adb_connected,
            "pid": self.process.pid if bot_running and self.process else None,
        }

    def _check_llama_health(self) -> bool:
        try:
            req = urllib.request.Request("http://127.0.0.1:8080/health", headers={"User-Agent": "BotManager"})
            with urllib.request.urlopen(req, timeout=1.5) as resp:
                data = resp.read().decode("utf-8", errors="ignore")
                return "ok" in data.lower()
        except Exception:
            return False

    def _check_adb_status(self) -> bool:
        try:
            res = subprocess.run(["adb", "devices"], capture_output=True, text=True, timeout=2)
            lines = res.stdout.strip().splitlines()
            for line in lines[1:]:
                if line.endswith("device"):
                    return True
            return False
        except Exception:
            return False

    def start(self, script_path: str = "./start.sh", adb_port: Optional[str] = None) -> bool:
        with self._lock:
            if self.is_running():
                return False

            self._log_buffer = ""
            full_script_path = os.path.abspath(os.path.join(self.workspace_dir, script_path)) if not os.path.isabs(script_path) else script_path

            env = os.environ.copy()
            if adb_port:
                env["PORT"] = str(adb_port)

            # Use pseudoterminal so output is unbuffered and interactive scripts run properly
            master_fd, slave_fd = pty.openpty()
            self._master_fd = master_fd

            import shutil
            shell_bin = shutil.which("bash") or shutil.which("sh") or "/bin/sh"

            try:
                self.process = subprocess.Popen(
                    [shell_bin, full_script_path],
                    stdin=slave_fd,
                    stdout=slave_fd,
                    stderr=slave_fd,
                    cwd=self.workspace_dir,
                    env=env,
                    preexec_fn=os.setsid,
                    close_fds=True,
                )
                os.close(slave_fd)
            except Exception as e:
                os.close(master_fd)
                os.close(slave_fd)
                self._log_buffer += f"[Error starting process: {e}]\n"
                return False

            # If an adb_port is supplied and script waits on prompt, write it automatically
            if adb_port:
                try:
                    time.sleep(0.3)
                    os.write(self._master_fd, f"{adb_port}\n".encode("utf-8"))
                except Exception:
                    pass

            self._reader_thread = threading.Thread(target=self._read_output, daemon=True)
            self._reader_thread.start()
            return True

    def _read_output(self):
        fd = self._master_fd
        if fd is None:
            return

        while self.is_running() or fd is not None:
            try:
                r, _, _ = select.select([fd], [], [], 0.2)
                if r:
                    data = os.read(fd, 2048)
                    if not data:
                        break
                    text = data.decode("utf-8", errors="replace")
                    with self._lock:
                        self._log_buffer += text
                        # Prevent memory ballooning; keep last 50,000 chars
                        if len(self._log_buffer) > 50000:
                            self._log_buffer = self._log_buffer[-40000:]
                elif not self.is_running():
                    break
            except (OSError, ValueError):
                break

        try:
            if self._master_fd is not None:
                os.close(self._master_fd)
                self._master_fd = None
        except Exception:
            pass

    def stop(self) -> bool:
        with self._lock:
            if not self.is_running() or self.process is None:
                return False

            try:
                # Terminate the process group
                pgid = os.getpgid(self.process.pid)
                os.killpg(pgid, signal.SIGTERM)
                time.sleep(0.5)
                if self.is_running():
                    os.killpg(pgid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            except Exception:
                try:
                    self.process.kill()
                except Exception:
                    pass

            self.process = None
            return True

    def get_logs(self, last_pos: int = 0) -> Tuple[str, int]:
        with self._lock:
            length = len(self._log_buffer)
            if last_pos >= length:
                return "", length
            chunk = self._log_buffer[last_pos:]
            return chunk, length

    def stream_logs(self) -> Generator[str, None, None]:
        pos = 0
        while True:
            chunk, pos = self.get_logs(pos)
            if chunk:
                for line in chunk.splitlines(keepends=True):
                    yield f"data: {line.strip()}\n\n"
            else:
                if not self.is_running():
                    yield "event: end\ndata: process terminated\n\n"
                    break
                time.sleep(0.5)
