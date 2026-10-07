import json
import pytest
from data_service import DataService

def test_config_operations(tmp_path):
    ds = DataService(workspace_dir=str(tmp_path))
    # Test reading non-existent
    assert ds.get_config("job_search") == {}

    # Test saving config
    sample_cfg = {"search_keyword": "python dev", "auto_submit": False}
    ok, err = ds.save_config("job_search", sample_cfg)
    assert ok is True
    assert err == ""

    # Test reading saved config
    loaded = ds.get_config("job_search")
    assert loaded["search_keyword"] == "python dev"
    assert loaded["auto_submit"] is False

def test_unhandled_and_learned_memory(tmp_path):
    ds = DataService(workspace_dir=str(tmp_path))
    # Write some unhandled questions
    unhandled_file = tmp_path / "unhandled_questions.json"
    unhandled_file.write_text(json.dumps(["Question 1", "Question 2"]))

    unhandled = ds.get_unhandled_questions()
    assert len(unhandled) == 2
    assert "Question 1" in unhandled

    # Learn question 1
    learned_ok = ds.learn_question("Question 1", "My Answer")
    assert learned_ok is True

    # Check that it removed from unhandled
    updated_unhandled = ds.get_unhandled_questions()
    assert "Question 1" not in updated_unhandled
    assert "Question 2" in updated_unhandled

    # Check learned memory
    memory = ds.get_learned_memory()
    assert memory["Question 1"] == "My Answer"

def test_training_data_paging(tmp_path):
    ds = DataService(workspace_dir=str(tmp_path))
    dataset_file = tmp_path / "training_dataset.jsonl"
    lines = [json.dumps({"prompt": f"p{i}", "completion": f"c{i}"}) for i in range(10)]
    dataset_file.write_text("\n".join(lines))

    res = ds.get_training_data(limit=5, offset=0)
    assert res["total"] == 10
    assert len(res["items"]) == 5
    assert res["items"][0]["prompt"] == "p0"
