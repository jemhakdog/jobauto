import json
import pytest
from app import create_app
from bot_manager import BotManager
from data_service import DataService

@pytest.fixture
def client(tmp_path):
    bm = BotManager(workspace_dir=str(tmp_path))
    ds = DataService(workspace_dir=str(tmp_path))
    app = create_app(bot_manager=bm, data_service=ds)
    app.config["TESTING"] = True
    with app.test_client() as client:
        yield client

def test_status_endpoint(client):
    res = client.get("/api/bot/status")
    assert res.status_code == 200
    data = res.get_json()
    assert "bot_running" in data
    assert "llama_server_running" in data

def test_config_endpoints(client, tmp_path):
    # Save config
    res = client.post("/api/config/job_search", json={"search_keyword": "va remote"})
    assert res.status_code == 200
    data = res.get_json()
    assert data["success"] is True

    # Get config
    res = client.get("/api/config/job_search")
    assert res.status_code == 200
    cfg = res.get_json()
    assert cfg["search_keyword"] == "va remote"

def test_memory_learn_endpoint(client, tmp_path):
    res = client.post("/api/data/memory/learn", json={"question": "Years in Python?", "answer": "3 years"})
    assert res.status_code == 200
    assert res.get_json()["success"] is True

    res = client.get("/api/data/memory")
    assert res.status_code == 200
    mem = res.get_json()
    assert mem["Years in Python?"] == "3 years"
