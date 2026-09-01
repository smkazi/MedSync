"""
Triage rules.

These are the tests that matter most in this service: an acuity that is one level too low delays a
sick patient, and one too high consumes a resuscitation bay someone else needs.
"""

from __future__ import annotations

import pytest

from app.schemas import AcuityLevel, TriageRequest, Vitals
from app.services.triage import assess


def acuity_for(complaint: str, age: int = 40, **vitals) -> AcuityLevel:
    level, _, _, _ = assess(TriageRequest(
        presenting_complaint=complaint, patient_age=age, vitals=Vitals(**vitals)
    ))
    return level


def drivers_for(complaint: str, age: int = 40, **vitals) -> list[str]:
    _, drivers, _, _ = assess(TriageRequest(
        presenting_complaint=complaint, patient_age=age, vitals=Vitals(**vitals)
    ))
    return drivers


class TestCriticalPresentations:

    def test_unresponsive_is_resuscitation(self):
        assert acuity_for("Found collapsed", consciousness="UNRESPONSIVE") == AcuityLevel.RESUSCITATION

    def test_severe_hypoxia_is_resuscitation(self):
        assert acuity_for("Breathless", oxygen_saturation=86) == AcuityLevel.RESUSCITATION

    def test_hypotension_is_resuscitation(self):
        assert acuity_for("Feels faint", systolic_bp=82) == AcuityLevel.RESUSCITATION

    def test_anaphylaxis_is_resuscitation_on_complaint_alone(self):
        # Vitals can still look acceptable early in anaphylaxis; the complaint has to carry it.
        assert acuity_for("Anaphylaxis after peanut, lip swelling", heart_rate=95,
                          systolic_bp=118) == AcuityLevel.RESUSCITATION

    def test_reported_arrest_is_resuscitation(self):
        assert acuity_for("Not breathing, CPR in progress") == AcuityLevel.RESUSCITATION


class TestEmergentPresentations:

    @pytest.mark.parametrize("complaint", [
        "Central chest pain radiating to jaw",
        "Sudden facial droop and slurred speech",
        "Took an overdose of paracetamol",
        "Vomiting blood, haematemesis",
        "Worst headache of my life with neck stiffness",
        "Bleeding in pregnancy at 8 weeks",
    ])
    def test_time_critical_complaints_are_emergent(self, complaint):
        assert acuity_for(complaint, heart_rate=88, systolic_bp=125) == AcuityLevel.EMERGENT

    def test_two_concerning_vitals_are_emergent_without_a_red_flag_complaint(self):
        assert acuity_for("Generally unwell", heart_rate=115, respiratory_rate=26) == AcuityLevel.EMERGENT


class TestNegation:
    """
    Negated findings must not raise acuity.

    "no fever" and "denies chest pain" are ordinary triage phrasing, and matching the term anyway
    over-triages the patient on a finding the clinician explicitly excluded.
    """

    def test_negated_fever_does_not_raise_acuity(self):
        assert acuity_for("Sore throat for two days, no fever", heart_rate=76, systolic_bp=118,
                          temperature_c=36.8, consciousness="ALERT") == AcuityLevel.LESS_URGENT

    def test_denied_chest_pain_does_not_raise_acuity(self):
        assert acuity_for("Post-op review, denies chest pain, mobilising well",
                          heart_rate=72, systolic_bp=124) == AcuityLevel.LESS_URGENT

    @pytest.mark.parametrize("phrasing", [
        "no chest pain",
        "not short of breath",
        "without chest pain",
        "nil chest pain reported",
        "negative for chest pain",
    ])
    def test_every_negation_cue_is_recognised(self, phrasing):
        assert acuity_for(f"Routine review, {phrasing}", heart_rate=70,
                          systolic_bp=120) == AcuityLevel.LESS_URGENT

    def test_a_real_finding_after_a_negated_one_still_counts(self):
        # A full stop ends the negation's scope: this patient does have chest pain.
        assert acuity_for("No fever. Severe chest pain since this morning", age=60,
                          heart_rate=92, systolic_bp=130) == AcuityLevel.EMERGENT

    def test_but_also_ends_the_negation_scope(self):
        assert acuity_for("No cough but severe shortness of breath", heart_rate=95,
                          systolic_bp=128) == AcuityLevel.EMERGENT


class TestAgeSensitivity:

    def test_paediatric_thresholds_are_not_adult_thresholds(self):
        # 135 bpm and 34/min are alarming in an adult and unremarkable in a toddler.
        child = acuity_for("Fever and fast breathing", age=3, heart_rate=135,
                          respiratory_rate=34, temperature_c=38.9)
        adult = acuity_for("Fever and fast breathing", age=30, heart_rate=135,
                           respiratory_rate=34, temperature_c=38.9)
        assert adult.value < child.value

    def test_elderly_borderline_case_is_escalated(self):
        younger = acuity_for("Fell at home, hip pain", age=40, heart_rate=88, systolic_bp=132)
        older = acuity_for("Fell at home, hip pain", age=82, heart_rate=88, systolic_bp=132)
        assert older.value < younger.value
        assert any("escalated" in driver for driver in
                   drivers_for("Fell at home, hip pain", age=82, heart_rate=88, systolic_bp=132))

    def test_infants_are_escalated(self):
        infant = acuity_for("Poor feeding", age=0, heart_rate=140, respiratory_rate=38)
        assert infant.value <= AcuityLevel.URGENT.value


class TestExplainability:

    def test_every_assessment_states_what_set_it(self):
        _, drivers, _, _ = assess(TriageRequest(
            presenting_complaint="Chest pain", patient_age=60,
            vitals=Vitals(heart_rate=118, oxygen_saturation=92),
        ))
        assert drivers, "an acuity with no stated driver cannot be challenged by the nurse using it"

    def test_confidence_rises_with_the_number_of_measured_vitals(self):
        _, _, _, sparse = assess(TriageRequest(presenting_complaint="Unwell", patient_age=40))
        _, _, _, complete = assess(TriageRequest(
            presenting_complaint="Unwell", patient_age=40,
            vitals=Vitals(heart_rate=80, respiratory_rate=16, systolic_bp=120,
                          oxygen_saturation=98, temperature_c=37.0, consciousness="ALERT"),
        ))
        assert complete > sparse

    def test_red_flags_are_reported_separately_from_drivers(self):
        _, _, red_flags, _ = assess(TriageRequest(
            presenting_complaint="Central chest pain", patient_age=55,
            vitals=Vitals(heart_rate=90),
        ))
        assert any("chest pain" in flag for flag in red_flags)
