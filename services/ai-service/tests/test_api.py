"""End-to-end checks over the HTTP surface, including the contract every response must honour."""

from __future__ import annotations


class TestHealth:

    def test_health_reports_what_is_loaded(self, client):
        body = client.get("/actuator/health").json()
        assert body["status"] == "UP"
        assert body["icd10Codes"] > 0

    def test_info_lists_the_capabilities(self, client):
        assert len(client.get("/actuator/info").json()["capabilities"]) == 4


class TestProvenanceContract:
    """Every response says how it was produced and carries the advisory disclaimer. A clinician
    must never be unable to tell a model's output from a rule's."""

    def _provenances(self, client) -> list[dict]:
        responses = [
            client.post("/ai/triage", json={"presenting_complaint": "chest pain",
                                            "patient_age": 55}),
            client.post("/ai/appointments/no-show-risk", json={"lead_time_days": 10,
                                                               "patient_age": 40}),
            client.post("/ai/icd10/suggest", json={"text": "high blood pressure"}),
            client.post("/ai/notes/summarize", json={"note_text": "Assessment: stable. "
                                                                  "Plan: continue treatment."}),
        ]
        for response in responses:
            assert response.status_code == 200, response.text
        return [response.json()["provenance"] for response in responses]

    def test_every_endpoint_reports_its_model(self, client):
        for provenance in self._provenances(client):
            assert provenance["model"]

    def test_every_endpoint_reports_whether_a_fallback_answered(self, client):
        for provenance in self._provenances(client):
            assert isinstance(provenance["fallback_used"], bool)

    def test_every_endpoint_carries_the_advisory_disclaimer(self, client):
        for provenance in self._provenances(client):
            assert "reviewed by a qualified clinician" in provenance["disclaimer"]

    def test_confidence_is_always_a_probability(self, client):
        for provenance in self._provenances(client):
            assert 0.0 <= provenance["confidence"] <= 1.0


class TestValidation:

    def test_a_note_that_is_too_short_is_rejected(self, client):
        assert client.post("/ai/notes/summarize", json={"note_text": "short"}).status_code == 422

    def test_an_impossible_age_is_rejected(self, client):
        response = client.post("/ai/triage", json={"presenting_complaint": "pain",
                                                   "patient_age": 500})
        assert response.status_code == 422

    def test_an_impossible_oxygen_saturation_is_rejected(self, client):
        response = client.post("/ai/triage", json={
            "presenting_complaint": "breathless", "patient_age": 40,
            "vitals": {"oxygen_saturation": 150},
        })
        assert response.status_code == 422

    def test_more_no_shows_than_appointments_is_still_scored_safely(self, client):
        # Bad upstream data must not produce a nonsensical probability.
        response = client.post("/ai/appointments/no-show-risk", json={
            "lead_time_days": 5, "patient_age": 30,
            "previous_appointments": 2, "previous_no_shows": 5,
        })
        assert response.status_code == 200
        assert 0.0 <= response.json()["risk_score"] <= 1.0


class TestCorrelationId:

    def test_the_correlation_id_is_echoed(self, client):
        response = client.get("/actuator/health",
                              headers={"X-Correlation-Id": "abc-123"})
        assert response.headers.get("X-Correlation-Id") == "abc-123"
