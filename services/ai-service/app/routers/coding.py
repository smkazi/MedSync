"""ICD-10 code suggestion endpoint."""

from __future__ import annotations

from fastapi import APIRouter, Depends, Request

from app.schemas import CodingRequest, CodingResponse, Provenance
from app.security import Caller, require_roles

router = APIRouter(prefix="/ai/icd10", tags=["coding"])


@router.post("/suggest", response_model=CodingResponse)
def suggest(
    payload: CodingRequest,
    request: Request,
    caller: Caller = Depends(require_roles("ADMIN", "DOCTOR", "NURSE", "PATHOLOGIST")),
) -> CodingResponse:
    """
    Suggests diagnosis codes for free text.

    Retrieval over a bundled subset, so every suggestion is a code that actually exists. Ranked
    for a human to accept - nothing is coded automatically.
    """
    index = request.app.state.icd10_index
    suggestions = index.suggest(payload.text, payload.max_suggestions)
    # Confidence tracks the top match's strength rather than being a fixed number.
    confidence = round(min(suggestions[0].score * 2.0, 0.9), 2) if suggestions else 0.1
    return CodingResponse(
        suggestions=suggestions,
        provenance=Provenance(model="icd10-tfidf-retrieval", fallback_used=False,
                              confidence=confidence),
    )
