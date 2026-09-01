"""Clinical note summarisation endpoint."""

from __future__ import annotations

import logging

from fastapi import APIRouter, Depends, Request

from app.config import Settings, get_settings
from app.schemas import NoteSummaryRequest, NoteSummaryResponse, Provenance
from app.security import Caller, require_roles
from app.services import summariser

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/ai/notes", tags=["notes"])


@router.post("/summarize", response_model=NoteSummaryResponse)
def summarize(
    payload: NoteSummaryRequest,
    request: Request,
    settings: Settings = Depends(get_settings),
    caller: Caller = Depends(require_roles("ADMIN", "DOCTOR", "NURSE")),
) -> NoteSummaryResponse:
    """
    Summarises a clinical note into the structured shape a clinician reads.

    Falls back to deterministic extraction when no model is reachable, and says so in the
    response - the reader is told whether a model or a rule produced what is on screen.
    """
    if settings.llm_enabled:
        try:
            summary = summariser.summarise_with_model(
                payload.note_text, settings, payload.patient_age, payload.patient_sex,
                payload.encounter_type,
            )
            return NoteSummaryResponse(
                result=summary,
                provenance=Provenance(model=settings.summary_model, fallback_used=False,
                                      confidence=0.8),
            )
        except Exception as exc:  # noqa: BLE001 - any model failure degrades, never fails the encounter
            logger.warning("Model summarisation failed for %s (%s); using extractive fallback",
                           caller.username, exc)

    return NoteSummaryResponse(
        result=summariser.extractive_summary(payload.note_text),
        provenance=Provenance(model="extractive-fallback", fallback_used=True, confidence=0.45),
    )
