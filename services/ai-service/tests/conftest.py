"""Shared fixtures. Authentication is disabled for the app-level tests, which exercise the
capabilities; token validation has its own dedicated tests."""

from __future__ import annotations

import os

import pytest

os.environ.setdefault("HMS_AI_AUTH_DISABLED", "true")
os.environ.setdefault("HMS_AI_ICD10_DATA", "data/icd10_subset.json")


@pytest.fixture(scope="session")
def client():
    from fastapi.testclient import TestClient

    from app.config import get_settings
    from app.main import app

    get_settings.cache_clear()
    with TestClient(app) as test_client:
        yield test_client
