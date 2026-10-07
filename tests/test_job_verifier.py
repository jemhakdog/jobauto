import json
from unittest.mock import patch, MagicMock
import pytest
from job_verifier import (
    fast_filter_job,
    verify_job_match,
    build_verification_prompt,
    parse_llm_verification_response
)

def test_fast_filter_disqualifies_senior_and_long_experience():
    # Senior role
    disqualified, reason = fast_filter_job("Senior Python Backend Engineer", "We need a Senior engineer with 7+ years of experience.")
    assert disqualified is True
    assert "senior" in reason.lower() or "experience" in reason.lower()

    # Lead role
    disqualified, reason = fast_filter_job("Tech Lead - Python", "Lead a team of engineers")
    assert disqualified is True

    # High experience required
    disqualified, reason = fast_filter_job("Python Developer", "Requires at least 5+ years experience in production.")
    assert disqualified is True

def test_fast_filter_allows_junior_and_entry_level():
    disqualified, reason = fast_filter_job("Junior Python Developer", "Entry-level position. Python, FastAPI, Git. Fresh grads welcome.")
    assert disqualified is False
    assert reason == ""

def test_parse_llm_verification_response():
    # Valid json response
    valid_json = '{"match": true, "score": 85, "reason": "Matches Python and FastAPI skills for junior candidate."}'
    is_match, score, reason = parse_llm_verification_response(valid_json)
    assert is_match is True
    assert score == 85
    assert "Python" in reason

    # Valid json markdown wrapped
    wrapped_json = '```json\n{"match": false, "score": 30, "reason": "Requires C++ embedded systems."}\n```'
    is_match, score, reason = parse_llm_verification_response(wrapped_json)
    assert is_match is False
    assert score == 30
    assert "C++" in reason

    # Malformed response fallback
    bad_resp = 'Not JSON at all, but says MATCH: YES'
    is_match, score, reason = parse_llm_verification_response(bad_resp)
    assert isinstance(is_match, bool)
    assert isinstance(score, int)

@patch("urllib.request.urlopen")
def test_verify_job_match_llm_success(mock_urlopen):
    mock_response = MagicMock()
    mock_response.read.return_value = json.dumps({
        "choices": [{
            "message": {
                "content": json.dumps({
                    "match": True,
                    "score": 80,
                    "reason": "Good match for junior python web developer"
                })
            }
        }]
    }).encode("utf-8")
    mock_response.__enter__.return_value = mock_response
    mock_urlopen.return_value = mock_response

    custom_cfg = {
        "job_verification": {
            "enabled": True,
            "engine": "local_llm",
            "min_score": 50
        }
    }

    is_match, reason, score = verify_job_match(
        "Junior Software Developer",
        "Looking for Junior Python / Web developer with HTML/CSS and basic SQL.",
        config=custom_cfg
    )

    assert is_match is True
    assert score == 80
    assert "match" in reason.lower()

def test_verify_job_match_fast_filter_rejection():
    custom_cfg = {
        "job_verification": {
            "enabled": True,
            "engine": "local_llm",
            "min_score": 50
        }
    }
    is_match, reason, score = verify_job_match(
        "Senior Cloud Architect (8+ years)",
        "Must have 8+ years AWS and Kubernetes architecture experience.",
        config=custom_cfg
    )
    assert is_match is False
    assert score == 0
    assert "fast filter" in reason.lower() or "senior" in reason.lower() or "experience" in reason.lower()

def test_verify_job_match_disabled():
    custom_cfg = {
        "job_verification": {
            "enabled": False
        }
    }
    is_match, reason, score = verify_job_match(
        "Senior Cloud Architect",
        "Description...",
        config=custom_cfg
    )
    assert is_match is True
    assert "disabled" in reason.lower()

@patch("job_verifier.verify_job_with_llm")
def test_verify_job_with_huggingface_fallback(mock_llm):
    mock_llm.return_value = (True, 75, "Fallback LLM matched")
    custom_cfg = {
        "job_verification": {
            "enabled": True,
            "engine": "huggingface",
            "min_score": 50,
            "hf_model_name": "test/model"
        }
    }
    is_match, reason, score = verify_job_match(
        "Junior Python Dev",
        "Junior python requirements",
        config=custom_cfg
    )
    assert is_match is True
    assert score == 75

@patch("form_filler.ScreenMap")
@patch("form_filler.verify_job_match")
@patch("form_filler.key_back")
def test_process_single_job_rejects_mismatch(mock_back, mock_verify, mock_screen):
    from form_filler import process_single_job
    fake_screen = MagicMock()
    fake_screen.valid = True
    fake_screen.all_text = "Senior Go Engineer at BigCorp"
    fake_screen.elements = []
    mock_screen.return_value = fake_screen

    # Mismatch returns false
    mock_verify.return_value = (False, "Senior role not suitable for junior profile", 20)

    result = process_single_job("Senior Go Engineer")
    assert result is False
    mock_back.assert_called_with("Exit Mismatched Job")

