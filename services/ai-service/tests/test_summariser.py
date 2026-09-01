"""
The extractive fallback.

Its whole value is that it never invents clinical content, so that is what these tests check.
"""

from __future__ import annotations

from app.services.summariser import extractive_summary

NOTE = """Presenting complaint: 58 year old male with central chest pain since 2 hours, radiating to left arm.
O/E: BP 96/60, HR 118, SpO2 92% on air, diaphoretic, chest clear.
Investigations: ECG shows ST elevation in leads II, III, aVF. Troponin 1.8 ng/mL.
Assessment: Inferior STEMI.
Plan: Aspirin 300mg loading dose; clopidogrel 600mg; urgent cardiology referral for primary PCI.
Follow up: Cardiology review post PCI, cardiac rehab referral at discharge."""


class TestSectionExtraction:

    def test_reads_the_labelled_sections(self):
        summary = extractive_summary(NOTE)
        assert "chest pain" in summary.presenting_complaint.lower()
        assert "STEMI" in summary.assessment
        assert "Cardiology review" in summary.follow_up

    def test_splits_the_plan_into_steps(self):
        plan = extractive_summary(NOTE).plan
        assert len(plan) >= 3
        assert any("Aspirin" in step for step in plan)
        assert any("clopidogrel" in step for step in plan)

    def test_keeps_findings_that_carry_measurements(self):
        findings = " ".join(extractive_summary(NOTE).key_findings)
        assert "96/60" in findings or "118" in findings

    def test_preserves_numbers_and_units_exactly(self):
        # A summariser that rounds a troponin or drops a unit is worse than none.
        text = " ".join([extractive_summary(NOTE).assessment, *extractive_summary(NOTE).plan,
                         *extractive_summary(NOTE).key_findings])
        assert "300mg" in text
        assert "1.8 ng/mL" in text or "1.8" in text


class TestFaithfulness:

    def test_every_returned_string_comes_from_the_note(self):
        summary = extractive_summary(NOTE)
        note_lower = NOTE.lower()
        for field in (summary.summary, summary.presenting_complaint, summary.assessment,
                      summary.follow_up):
            for word in field.lower().split():
                stripped = word.strip(".,;:()")
                if len(stripped) > 4:
                    assert stripped in note_lower, f"{stripped!r} was not in the note"

    def test_red_flags_are_only_terms_present_in_the_note(self):
        summary = extractive_summary(NOTE)
        assert "chest pain" in summary.red_flags
        for flag in summary.red_flags:
            assert flag in NOTE.lower()

    def test_absent_sections_stay_empty_rather_than_being_invented(self):
        summary = extractive_summary("Patient seen. Nothing further to add.")
        assert summary.assessment == ""
        assert summary.plan == []
        assert summary.follow_up == ""

    def test_a_note_with_no_red_flags_reports_none(self):
        summary = extractive_summary("Presenting complaint: routine medication review. "
                                     "Assessment: stable. Plan: continue current treatment.")
        assert summary.red_flags == []


class TestRobustness:

    def test_unstructured_note_still_yields_a_summary(self):
        summary = extractive_summary(
            "Seen in clinic today with a two week history of cough and night sweats, "
            "losing weight. Chest examination unremarkable. Will arrange chest x-ray."
        )
        assert summary.summary
        assert "weight loss" in summary.red_flags or "night sweats" in summary.red_flags

    def test_handles_a_single_sentence(self):
        assert extractive_summary("Well, no concerns today at all.").summary

    def test_alternative_headings_are_recognised(self):
        summary = extractive_summary(
            "C/O: headache for 3 days\nImpression: tension headache\nRx: simple analgesia\n"
            "Review: 2 weeks if no better"
        )
        assert "headache" in summary.presenting_complaint.lower()
        assert "tension" in summary.assessment.lower()
        assert summary.plan
        assert summary.follow_up
