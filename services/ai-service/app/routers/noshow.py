"""Appointment no-show risk endpoint."""

from __future__ import annotations

from fastapi import APIRouter, Depends, Request

from app.schemas import NoShowRequest, NoShowResponse, Provenance
from app.security import Caller, require_roles
from app.services import noshow as noshow_service

router = APIRouter(prefix="/ai/appointments", tags=["appointments"])


@router.post("/no-show-risk", response_model=NoShowResponse)
def no_show_risk(
    payload: NoShowRequest,
    request: Request,
    caller: Caller = Depends(require_roles("ADMIN", "DOCTOR", "NURSE", "RECEPTIONIST")),
) -> NoShowResponse:
    """
    Scores the probability that a patient does not attend, with the factors behind it.

    The score is a scheduling aid. It must never be used to deny or deprioritise care - the
    recommended actions are all about making it easier to attend.
    """
    model = request.app.state.noshow_model
    probability, drivers, fallback_used, confidence = noshow_service.score(payload, model)
    return NoShowResponse(
        risk_score=probability,
        risk_band=noshow_service.band(probability),
        drivers=drivers,
        recommended_action=noshow_service.recommended_action(probability, payload),
        provenance=Provenance(
            model="noshow-gbdt-calibrated" if not fallback_used else "noshow-logistic-fallback",
            fallback_used=fallback_used,
            confidence=confidence,
        ),
    )
