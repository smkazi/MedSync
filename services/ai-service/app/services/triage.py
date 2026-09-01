"""
Triage acuity scoring.

An ESI-style rule engine over vitals and presenting complaint. This is deliberately rules-first
rather than a learned model: triage decisions must be explainable to the nurse making them and
auditable afterwards, and the physiological thresholds that define instability are established
clinical knowledge, not something to rediscover from data.

The score never lowers acuity below what a red flag demands - a model that talks a nurse out of
escalating would be worse than no model.
"""

from __future__ import annotations

import re

from app.schemas import AcuityLevel, TriageRequest, Vitals
from app.services.negation import is_negated

#: How soon each acuity level should be assessed, in minutes.
TARGET_MINUTES: dict[AcuityLevel, int] = {
    AcuityLevel.RESUSCITATION: 0,
    AcuityLevel.EMERGENT: 10,
    AcuityLevel.URGENT: 30,
    AcuityLevel.LESS_URGENT: 60,
    AcuityLevel.NON_URGENT: 120,
}

LABELS: dict[AcuityLevel, str] = {
    AcuityLevel.RESUSCITATION: "Resuscitation - immediate life-saving intervention",
    AcuityLevel.EMERGENT: "Emergent - high risk, see within minutes",
    AcuityLevel.URGENT: "Urgent - needs timely assessment",
    AcuityLevel.LESS_URGENT: "Less urgent",
    AcuityLevel.NON_URGENT: "Non-urgent",
}

DISPOSITIONS: dict[AcuityLevel, str] = {
    AcuityLevel.RESUSCITATION: "Resuscitation bay now; alert the senior clinician",
    AcuityLevel.EMERGENT: "Monitored bed; senior review within 10 minutes",
    AcuityLevel.URGENT: "Assessment area; observations repeated every 30 minutes",
    AcuityLevel.LESS_URGENT: "Standard waiting area with routine observations",
    AcuityLevel.NON_URGENT: "Standard waiting area; consider primary care redirection",
}

#: Complaints that carry immediate risk regardless of how the vitals read on arrival.
_CRITICAL_COMPLAINTS: tuple[tuple[str, str], ...] = (
    (r"cardiac arrest|not breathing|unresponsive", "reported arrest or unresponsiveness"),
    (r"anaphylax|airway swelling|stridor", "airway compromise"),
    (r"active seizure|status epilepticus", "ongoing seizure"),
    (r"severe h[ae]emorrhage|uncontrolled bleeding|exsanguinat", "uncontrolled haemorrhage"),
)

#: Complaints that warrant emergent handling: time-critical pathways.
_EMERGENT_COMPLAINTS: tuple[tuple[str, str], ...] = (
    (r"chest pain|crushing pain|central pain", "chest pain - rule out acute coronary syndrome"),
    (r"stroke|facial droop|slurred speech|weakness one side|hemipares",
     "possible stroke - time-critical pathway"),
    (r"overdose|poison|suicidal|self.?harm", "self-harm or poisoning risk"),
    (r"h[ae]ematemesis|melaena|melena", "gastrointestinal bleeding"),
    (r"pregnan.*bleed|bleeding.*pregnan|ectopic", "bleeding in pregnancy"),
    (r"worst headache|thunderclap|neck stiffness|photophobia",
     "headache with features of subarachnoid haemorrhage or meningitis"),
    (r"shortness of breath|breathless|dyspn", "respiratory distress"),
    (r"sepsis|septic", "possible sepsis"),
)

_URGENT_COMPLAINTS: tuple[tuple[str, str], ...] = (
    (r"abdominal pain|abdo pain", "abdominal pain"),
    (r"fracture|dislocat|deformity", "suspected fracture or dislocation"),
    (r"vomiting|diarrh?o?ea|dehydrat", "fluid loss"),
    (r"fever|pyrexia|temperature", "febrile illness"),
    (r"laceration|wound|burn", "wound requiring care"),
)


def _abnormal_vitals(vitals: Vitals, age: int) -> tuple[list[str], list[str]]:
    """
    Assesses vitals against age-appropriate thresholds.

    Returns (critical, concerning). Children breathe and beat faster than adults, so a single
    adult threshold would systematically under-triage them.
    """
    critical: list[str] = []
    concerning: list[str] = []

    if vitals.consciousness:
        level = vitals.consciousness.strip().upper()
        if level == "UNRESPONSIVE":
            critical.append("Unresponsive (AVPU: U)")
        elif level == "PAIN":
            critical.append("Responds only to pain (AVPU: P)")
        elif level == "VOICE":
            concerning.append("Responds only to voice (AVPU: V)")

    if vitals.oxygen_saturation is not None:
        if vitals.oxygen_saturation < 90:
            critical.append(f"SpO2 {vitals.oxygen_saturation}% - severe hypoxia")
        elif vitals.oxygen_saturation < 94:
            concerning.append(f"SpO2 {vitals.oxygen_saturation}% - hypoxia")

    # Paediatric thresholds for the under-12s; adult thresholds above that.
    paediatric = age < 12
    if vitals.heart_rate is not None:
        upper_critical, upper_concern = (180, 140) if paediatric else (130, 110)
        lower_critical = 60 if paediatric else 40
        if vitals.heart_rate >= upper_critical or vitals.heart_rate <= lower_critical:
            critical.append(f"Heart rate {vitals.heart_rate} bpm")
        elif vitals.heart_rate >= upper_concern:
            concerning.append(f"Heart rate {vitals.heart_rate} bpm - tachycardia")

    if vitals.respiratory_rate is not None:
        upper_critical, upper_concern = (60, 40) if paediatric else (30, 24)
        if vitals.respiratory_rate >= upper_critical or vitals.respiratory_rate < 8:
            critical.append(f"Respiratory rate {vitals.respiratory_rate}/min")
        elif vitals.respiratory_rate >= upper_concern:
            concerning.append(f"Respiratory rate {vitals.respiratory_rate}/min - tachypnoea")

    if vitals.systolic_bp is not None and not paediatric:
        if vitals.systolic_bp < 90:
            critical.append(f"Systolic BP {vitals.systolic_bp} mmHg - hypotension")
        elif vitals.systolic_bp >= 220:
            concerning.append(f"Systolic BP {vitals.systolic_bp} mmHg - severe hypertension")

    if vitals.temperature_c is not None:
        if vitals.temperature_c >= 41.0 or vitals.temperature_c <= 35.0:
            critical.append(f"Temperature {vitals.temperature_c} C")
        elif vitals.temperature_c >= 38.5:
            concerning.append(f"Temperature {vitals.temperature_c} C - fever")

    if vitals.pain_score is not None and vitals.pain_score >= 8:
        concerning.append(f"Pain score {vitals.pain_score}/10 - severe pain")

    return critical, concerning


def _match(patterns: tuple[tuple[str, str], ...], complaint: str) -> list[str]:
    """
    Which patterns the complaint actually asserts.

    A pattern counts only if at least one of its occurrences is un-negated.
    """
    reasons: list[str] = []
    for pattern, reason in patterns:
        matches = list(re.finditer(pattern, complaint))
        if matches and any(not is_negated(complaint, m.start()) for m in matches):
            reasons.append(reason)
    return reasons


def assess(request: TriageRequest) -> tuple[AcuityLevel, list[str], list[str], float]:
    """
    Assigns an acuity level.

    @return (acuity, drivers, red_flags, confidence)
    """
    complaint = request.presenting_complaint.lower()
    critical_vitals, concerning_vitals = _abnormal_vitals(request.vitals, request.patient_age)

    critical_complaints = _match(_CRITICAL_COMPLAINTS, complaint)
    emergent_complaints = _match(_EMERGENT_COMPLAINTS, complaint)
    urgent_complaints = _match(_URGENT_COMPLAINTS, complaint)

    red_flags = critical_vitals + critical_complaints + emergent_complaints
    drivers: list[str] = []

    if critical_vitals or critical_complaints:
        acuity = AcuityLevel.RESUSCITATION
        drivers = critical_vitals + critical_complaints
    elif emergent_complaints or len(concerning_vitals) >= 2:
        acuity = AcuityLevel.EMERGENT
        drivers = emergent_complaints + concerning_vitals
    elif concerning_vitals or urgent_complaints:
        acuity = AcuityLevel.URGENT
        drivers = concerning_vitals + urgent_complaints
    elif complaint.strip():
        acuity = AcuityLevel.LESS_URGENT
        drivers = ["No abnormal vitals recorded and no red-flag features in the complaint"]
    else:
        acuity = AcuityLevel.NON_URGENT
        drivers = ["No complaint or vitals supplied"]

    # Age escalation: the very young and the very old decompensate faster, so a borderline
    # presentation is moved up one level rather than left to be re-triaged later.
    if acuity.value >= 3 and (request.patient_age < 1 or request.patient_age >= 75):
        escalated = AcuityLevel(acuity.value - 1)
        drivers.append(
            f"Age {request.patient_age} - escalated from {acuity.value} to {escalated.value}"
        )
        acuity = escalated

    # Confidence reflects how much the decision rests on measurements rather than free text.
    measured = sum(
        1 for value in (
            request.vitals.heart_rate, request.vitals.respiratory_rate,
            request.vitals.systolic_bp, request.vitals.oxygen_saturation,
            request.vitals.temperature_c, request.vitals.consciousness,
        ) if value is not None
    )
    confidence = round(min(0.55 + 0.07 * measured, 0.95), 2)
    if not drivers:
        confidence = 0.4

    return acuity, drivers, sorted(set(red_flags)), confidence
