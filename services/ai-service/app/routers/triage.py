"""Triage acuity endpoint."""

from __future__ import annotations

from fastapi import APIRouter, Depends

from app.schemas import Provenance, TriageRequest, TriageResponse
from app.security import Caller, require_roles
from app.services import triage as triage_service

router = APIRouter(prefix="/ai/triage", tags=["triage"])


@router.post("", response_model=TriageResponse)
def assess(
    payload: TriageRequest,
    caller: Caller = Depends(require_roles("ADMIN", "DOCTOR", "NURSE", "RECEPTIONIST")),
) -> TriageResponse:
    """
    Assigns an ESI-style acuity from vitals and presenting complaint.

    Every answer states exactly what set the level, because the nurse using it has to be able to
    disagree with it on the spot.
    """
    acuity, drivers, red_flags, confidence = triage_service.assess(payload)
    return TriageResponse(
        acuity=acuity,
        acuity_label=triage_service.LABELS[acuity],
        target_assessment_minutes=triage_service.TARGET_MINUTES[acuity],
        drivers=drivers,
        red_flags=red_flags,
        recommended_disposition=triage_service.DISPOSITIONS[acuity],
        provenance=Provenance(model="esi-rules-v1", fallback_used=False, confidence=confidence),
    )
