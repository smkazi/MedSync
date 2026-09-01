"""
Token validation.

This service is a resource server, so these tests are about what it refuses.
"""

from __future__ import annotations

import os

import pytest
from fastapi import HTTPException


@pytest.fixture
def app_client():
    """A client with authentication ON, unlike the shared fixture."""
    from fastapi.testclient import TestClient

    from app.config import get_settings

    os.environ["HMS_AI_AUTH_DISABLED"] = "false"
    get_settings.cache_clear()
    from app.main import app

    with TestClient(app) as client:
        yield client
    os.environ["HMS_AI_AUTH_DISABLED"] = "true"
    get_settings.cache_clear()


class TestRejection:

    def test_no_authorization_header_is_rejected(self, app_client):
        response = app_client.post("/ai/triage",
                                   json={"presenting_complaint": "chest pain", "patient_age": 50})
        assert response.status_code == 401
        assert response.headers.get("WWW-Authenticate") == "Bearer"

    def test_a_non_bearer_scheme_is_rejected(self, app_client):
        response = app_client.post("/ai/triage", headers={"Authorization": "Basic dXNlcjpwYXNz"},
                                   json={"presenting_complaint": "chest pain", "patient_age": 50})
        assert response.status_code == 401

    def test_a_malformed_token_is_rejected(self, app_client):
        response = app_client.post("/ai/triage", headers={"Authorization": "Bearer not.a.jwt"},
                                   json={"presenting_complaint": "chest pain", "patient_age": 50})
        assert response.status_code == 401

    def test_an_unsigned_token_is_rejected(self, app_client):
        # alg=none is the classic JWT bypass; the decoder is pinned to RS256.
        import base64
        import json

        def segment(payload: dict) -> str:
            raw = json.dumps(payload).encode()
            return base64.urlsafe_b64encode(raw).decode().rstrip("=")

        forged = ".".join([
            segment({"alg": "none", "typ": "JWT"}),
            segment({"sub": "attacker", "roles": ["ADMIN"], "iss": "http://localhost:8081",
                     "aud": "hms", "exp": 9999999999, "iat": 1}),
            "",
        ])
        response = app_client.post("/ai/triage", headers={"Authorization": f"Bearer {forged}"},
                                   json={"presenting_complaint": "chest pain", "patient_age": 50})
        assert response.status_code == 401

    def test_health_is_reachable_without_a_token(self, app_client):
        assert app_client.get("/actuator/health").status_code == 200


class TestRoleEnforcement:

    def test_require_roles_rejects_a_caller_without_the_role(self):
        from app.security import Caller, require_roles

        dependency = require_roles("DOCTOR", "NURSE")
        receptionist = Caller(user_id="1", username="reception", roles=frozenset({"RECEPTIONIST"}))

        with pytest.raises(HTTPException) as raised:
            dependency(caller=receptionist)
        assert raised.value.status_code == 403

    def test_require_roles_accepts_a_caller_with_any_listed_role(self):
        from app.security import Caller, require_roles

        dependency = require_roles("DOCTOR", "NURSE")
        nurse = Caller(user_id="1", username="nurse", roles=frozenset({"NURSE"}))
        assert dependency(caller=nurse) is nurse
