"""Request and response shapes for the clinical decision-support endpoints."""

from __future__ import annotations

from enum import Enum

from pydantic import BaseModel, Field

#: Attached to every response. These endpoints support a clinician's judgement; they never
#: replace it, and nothing here writes to a patient record on its own.
DISCLAIMER = (
    "Clinical decision support only. Output is advisory, may be incomplete or wrong, and must be "
    "reviewed by a qualified clinician before it informs care."
)


class Provenance(BaseModel):
    """How an answer was produced, so a clinician can weigh it."""

    model: str = Field(description="The model or method that produced this result")
    fallback_used: bool = Field(
        default=False,
        description="True when the deterministic fallback answered because no model was reachable",
    )
    confidence: float = Field(ge=0.0, le=1.0, description="0-1 confidence in this result")
    disclaimer: str = DISCLAIMER


# --------------------------------------------------------------------------- notes


class NoteSummaryRequest(BaseModel):
    note_text: str = Field(min_length=10, max_length=20000, description="The clinical note to summarise")
    patient_age: int | None = Field(default=None, ge=0, le=130)
    patient_sex: str | None = Field(default=None, max_length=16)
    encounter_type: str | None = Field(default=None, max_length=64)


class NoteSummary(BaseModel):
    """
    A visit summary in the shape a clinician reads.

    The field names mirror a SOAP note so the output slots straight into the chart rather than
    arriving as a wall of prose.
    """

    summary: str = Field(description="Two or three sentences a colleague could read at handover")
    presenting_complaint: str = Field(default="", description="Why the patient attended")
    key_findings: list[str] = Field(default_factory=list, description="Objective findings that matter")
    assessment: str = Field(default="", description="The clinical impression as recorded")
    plan: list[str] = Field(default_factory=list, description="Agreed next steps")
    follow_up: str = Field(default="", description="When and with whom the patient is seen next")
    red_flags: list[str] = Field(
        default_factory=list,
        description="Findings in the note that warrant urgent attention",
    )


class NoteSummaryResponse(BaseModel):
    result: NoteSummary
    provenance: Provenance


# --------------------------------------------------------------------------- no-show


class NoShowRequest(BaseModel):
    lead_time_days: int = Field(ge=0, le=365, description="Days between booking and appointment")
    patient_age: int = Field(ge=0, le=130)
    previous_appointments: int = Field(default=0, ge=0, le=500)
    previous_no_shows: int = Field(default=0, ge=0, le=500)
    hour_of_day: int = Field(default=10, ge=0, le=23)
    day_of_week: int = Field(default=2, ge=0, le=6, description="0 = Monday")
    is_first_visit: bool = False
    has_reminder_contact: bool = True
    distance_km: float = Field(default=5.0, ge=0.0, le=500.0)
    priority: str = Field(default="ROUTINE", max_length=16)


class RiskDriver(BaseModel):
    """One factor and the direction it pushed the score, so the number is explainable."""

    feature: str
    contribution: float = Field(description="Positive raises the risk, negative lowers it")
    detail: str


class NoShowResponse(BaseModel):
    risk_score: float = Field(ge=0.0, le=1.0, description="Probability the patient does not attend")
    risk_band: str = Field(description="LOW, MEDIUM or HIGH")
    drivers: list[RiskDriver]
    recommended_action: str
    provenance: Provenance


# --------------------------------------------------------------------------- triage


class Vitals(BaseModel):
    heart_rate: int | None = Field(default=None, ge=0, le=300, description="beats per minute")
    systolic_bp: int | None = Field(default=None, ge=0, le=300, description="mmHg")
    diastolic_bp: int | None = Field(default=None, ge=0, le=200, description="mmHg")
    respiratory_rate: int | None = Field(default=None, ge=0, le=90, description="breaths per minute")
    temperature_c: float | None = Field(default=None, ge=20.0, le=45.0)
    oxygen_saturation: int | None = Field(default=None, ge=0, le=100, description="SpO2 %")
    consciousness: str | None = Field(
        default=None,
        description="AVPU: ALERT, VOICE, PAIN or UNRESPONSIVE",
        max_length=16,
    )
    pain_score: int | None = Field(default=None, ge=0, le=10)


class TriageRequest(BaseModel):
    presenting_complaint: str = Field(min_length=2, max_length=2000)
    patient_age: int = Field(ge=0, le=130)
    patient_sex: str | None = Field(default=None, max_length=16)
    vitals: Vitals = Field(default_factory=Vitals)


class AcuityLevel(int, Enum):
    """
    ESI-style acuity. 1 is the sickest.

    1 resuscitation, 2 emergent, 3 urgent, 4 less urgent, 5 non-urgent.
    """

    RESUSCITATION = 1
    EMERGENT = 2
    URGENT = 3
    LESS_URGENT = 4
    NON_URGENT = 5


class TriageResponse(BaseModel):
    acuity: AcuityLevel
    acuity_label: str
    target_assessment_minutes: int = Field(description="How soon this patient should be seen")
    drivers: list[str] = Field(description="Exactly what set this acuity, in plain language")
    red_flags: list[str]
    recommended_disposition: str
    provenance: Provenance


# --------------------------------------------------------------------------- coding


class CodingRequest(BaseModel):
    text: str = Field(min_length=3, max_length=8000, description="Diagnosis text or note excerpt")
    max_suggestions: int = Field(default=5, ge=1, le=20)


class CodeSuggestion(BaseModel):
    code: str
    description: str
    score: float = Field(ge=0.0, le=1.0)
    matched_terms: list[str]


class CodingResponse(BaseModel):
    suggestions: list[CodeSuggestion]
    provenance: Provenance
