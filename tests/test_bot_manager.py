import pytest
import time
from bot_manager import BotManager

def test_bot_manager_init():
    bm = BotManager()
    assert bm.is_running() is False
    status = bm.get_status()
    assert status["bot_running"] is False
    assert "llama_server_running" in status
    assert "adb_connected" in status

def test_bot_manager_start_and_stop(tmp_path):
    # Dummy shell script that echoes output and sleeps
    dummy_sh = tmp_path / "dummy_start.sh"
    dummy_sh.write_text("#!/bin/sh\necho 'Starting dummy bot'\nsleep 5\necho 'Done dummy bot'\n")
    dummy_sh.chmod(0o755)

    bm = BotManager()
    started = bm.start(script_path=str(dummy_sh))
    assert started is True
    assert bm.is_running() is True

    # Starting again while running should fail
    second_start = bm.start(script_path=str(dummy_sh))
    assert second_start is False

    # Stop process
    stopped = bm.stop()
    assert stopped is True
    assert bm.is_running() is False

def test_bot_manager_logs(tmp_path):
    dummy_sh = tmp_path / "dummy_start.sh"
    dummy_sh.write_text("#!/bin/sh\necho 'Line 1'\nsleep 0.2\necho 'Line 2'\n")
    dummy_sh.chmod(0o755)

    bm = BotManager()
    bm.start(script_path=str(dummy_sh))
    time.sleep(0.5)
    logs, pos = bm.get_logs(last_pos=0)
    assert "Line 1" in logs
    bm.stop()
