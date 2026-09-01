"""
ai-service - clinical decision support for the MedSync platform.

Four capabilities, one rule: nothing here writes to a patient record, and every response says how
it was produced. A clinician accepts a suggestion; the platform never acts on one by itself.
"""

from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from app.config import get_settings
from app.routers import coding, noshow, notes, triage
from app.services.icd10 import Icd10Index
from app.services.noshow import load_model

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)-5s [%(name)s] %(message)s",
)
logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Loads the models once at startup rather than per request."""
    settings = get_settings()
    app.state.settings = settings
    app.state.noshow_model = load_model(settings.model_dir)
    app.state.icd10_index = Icd10Index.load(settings.icd10_data)
    logger.info(
        "ai-service ready (llm=%s, no-show model=%s, icd10 codes=%d)",
        "configured" if settings.llm_enabled else "fallback only",
        "trained" if app.state.noshow_model.available else "logistic fallback",
        app.state.icd10_index.size,
    )
    yield


app = FastAPI(
    title="MedSync AI service",
    version="0.1.0",
    description=(
        "Clinical decision support: note summarisation, no-show risk, triage acuity and ICD-10 "
        "suggestion. Advisory only - every response is reviewed by a clinician."
    ),
    lifespan=lifespan,
)

app.include_router(notes.router)
app.include_router(noshow.router)
app.include_router(triage.router)
app.include_router(coding.router)


@app.middleware("http")
async def correlation_id(request: Request, call_next):
    """Echoes the platform's correlation id so one request is traceable across every service."""
    header = "X-Correlation-Id"
    incoming = request.headers.get(header)
    response = await call_next(request)
    if incoming:
        response.headers[header] = incoming
    return response


@app.exception_handler(Exception)
async def unhandled_exception(request: Request, exc: Exception) -> JSONResponse:
    """Never leak a stack trace or a fragment of clinical text in an error body."""
    logger.exception("Unhandled error on %s", request.url.path)
    return JSONResponse(
        status_code=500,
        content={
            "title": "Internal Server Error",
            "status": 500,
            "detail": "An unexpected error occurred while processing this request",
            "instance": request.url.path,
        },
    )


@app.get("/actuator/health", tags=["ops"])
def health() -> dict:
    """Matches the Java services' health path so one probe configuration covers the platform."""
    settings = get_settings()
    return {
        "status": "UP",
        "service": settings.service_name,
        "llm": "configured" if settings.llm_enabled else "fallback",
        "noshowModel": "trained" if app.state.noshow_model.available else "fallback",
        "icd10Codes": app.state.icd10_index.size,
    }


@app.get("/actuator/info", tags=["ops"])
def info() -> dict:
    settings = get_settings()
    return {
        "name": settings.service_name,
        "capabilities": [
            "notes.summarize", "appointments.no-show-risk", "triage", "icd10.suggest",
        ],
        "summaryModel": settings.summary_model,
    }
