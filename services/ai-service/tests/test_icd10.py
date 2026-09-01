"""ICD-10 retrieval. Every suggestion must be a code that exists, and the obvious clinical phrase
must find the obvious code."""

from __future__ import annotations

import pytest

from app.services.icd10 import Icd10Index


@pytest.fixture(scope="module")
def index() -> Icd10Index:
    return Icd10Index.load("data/icd10_subset.json")


class TestRetrieval:

    @pytest.mark.parametrize(("query", "expected_code"), [
        ("crushing central chest pain radiating to left arm", "I21.9"),
        ("raised blood sugar and high hba1c", "E11.9"),
        ("burning micturition and frequency", "N39.0"),
        ("low haemoglobin with pallor and fatigue", "D50.9"),
        ("high blood pressure", "I10"),
        ("wheeze and reversible airway obstruction", "J45.9"),
        ("productive cough with fever and consolidation", "J18.9"),
        ("right iliac fossa pain with fever", "K35.80"),
        ("loin to groin pain", "N20.0"),
        ("facial droop and slurred speech", "I63.9"),
    ])
    def test_clinical_phrases_find_the_right_code(self, index, query, expected_code):
        codes = [s.code for s in index.suggest(query, limit=3)]
        assert expected_code in codes, f"{query!r} returned {codes}"

    def test_misspellings_still_retrieve(self, index):
        # Character n-grams are why this works; word matching alone would return nothing.
        codes = [s.code for s in index.suggest("diabetis", limit=3)]
        assert any(code.startswith("E1") for code in codes)

    def test_abbreviations_are_understood(self, index):
        assert "N39.0" in [s.code for s in index.suggest("uti", limit=3)]
        assert "J44.9" in [s.code for s in index.suggest("copd", limit=3)]


class TestOutputContract:

    def test_results_are_ranked(self, index):
        suggestions = index.suggest("chest pain", limit=5)
        scores = [s.score for s in suggestions]
        assert scores == sorted(scores, reverse=True)

    def test_limit_is_respected(self, index):
        assert len(index.suggest("pain", limit=2)) <= 2

    def test_every_suggestion_exists_in_the_classification(self, index):
        # Retrieval rather than generation is the point: no invented codes.
        import json
        import pathlib
        known = {entry["code"] for entry in
                 json.loads(pathlib.Path("data/icd10_subset.json").read_text())["codes"]}
        for suggestion in index.suggest("fever cough headache abdominal pain", limit=10):
            assert suggestion.code in known

    def test_matched_terms_justify_the_suggestion(self, index):
        suggestion = index.suggest("high blood pressure", limit=1)[0]
        assert suggestion.matched_terms

    def test_nonsense_returns_nothing_rather_than_a_bad_guess(self, index):
        assert index.suggest("zzzzqqqqxxxx", limit=5) == []

    def test_empty_query_returns_nothing(self, index):
        assert index.suggest("   ", limit=5) == []
