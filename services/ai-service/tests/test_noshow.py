"""No-show risk scoring. The score is presented to clinic staff as a probability, so it has to
order patients sensibly and explain itself."""

from __future__ import annotations

from app.schemas import NoShowRequest
from app.services.noshow import NoShowModel, band, load_model, recommended_action, score

FALLBACK = NoShowModel(estimator=None, feature_names=None)


def request(**overrides) -> NoShowRequest:
    base = {
        "lead_time_days": 7, "patient_age": 40, "previous_appointments": 5,
        "previous_no_shows": 0, "hour_of_day": 11, "day_of_week": 2,
        "has_reminder_contact": True, "distance_km": 5.0, "priority": "ROUTINE",
    }
    return NoShowRequest(**{**base, **overrides})


class TestOrdering:
    """Absolute calibration is a property of the training data; ordering is a property of the
    model, and it is what a clinic acts on."""

    def test_prior_no_shows_raise_the_score(self):
        clean, _, _, _ = score(request(previous_no_shows=0), FALLBACK)
        repeat, _, _, _ = score(request(previous_no_shows=4), FALLBACK)
        assert repeat > clean

    def test_longer_lead_time_raises_the_score(self):
        soon, _, _, _ = score(request(lead_time_days=2), FALLBACK)
        distant, _, _, _ = score(request(lead_time_days=60), FALLBACK)
        assert distant > soon

    def test_reminder_contact_lowers_the_score(self):
        reachable, _, _, _ = score(request(has_reminder_contact=True), FALLBACK)
        unreachable, _, _, _ = score(request(has_reminder_contact=False), FALLBACK)
        assert unreachable > reachable

    def test_urgent_appointments_are_kept(self):
        routine, _, _, _ = score(request(priority="ROUTINE"), FALLBACK)
        urgent, _, _, _ = score(request(priority="URGENT"), FALLBACK)
        assert urgent < routine

    def test_distance_raises_the_score(self):
        near, _, _, _ = score(request(distance_km=1.0), FALLBACK)
        far, _, _, _ = score(request(distance_km=80.0), FALLBACK)
        assert far > near


class TestOutputShape:

    def test_score_is_a_probability(self):
        value, _, _, _ = score(request(), FALLBACK)
        assert 0.0 <= value <= 1.0

    def test_drivers_are_returned_and_ordered_by_magnitude(self):
        _, drivers, _, _ = score(request(previous_no_shows=4, lead_time_days=60,
                                         has_reminder_contact=False), FALLBACK)
        assert drivers, "a risk score with no explanation is not actionable"
        magnitudes = [abs(driver.contribution) for driver in drivers]
        assert magnitudes == sorted(magnitudes, reverse=True)

    def test_each_driver_carries_a_readable_detail(self):
        _, drivers, _, _ = score(request(previous_no_shows=3, previous_appointments=6), FALLBACK)
        prior = next(d for d in drivers if d.feature == "prior_no_show_rate")
        assert "3" in prior.detail and "6" in prior.detail

    def test_fallback_is_flagged_as_such(self):
        _, _, fallback_used, _ = score(request(), FALLBACK)
        assert fallback_used is True

    def test_no_prior_appointments_does_not_divide_by_zero(self):
        value, _, _, _ = score(request(previous_appointments=0, previous_no_shows=0), FALLBACK)
        assert 0.0 <= value <= 1.0


class TestBandsAndActions:

    def test_bands_are_ordered(self):
        assert band(0.05) == "LOW"
        assert band(0.30) == "MEDIUM"
        assert band(0.75) == "HIGH"

    def test_high_risk_without_contact_asks_for_a_phone_number_first(self):
        action = recommended_action(0.6, request(has_reminder_contact=False))
        assert "phone number" in action

    def test_recommended_actions_only_ever_make_attendance_easier(self):
        # The score must never be used to deny or deprioritise care.
        for probability in (0.05, 0.3, 0.8):
            action = recommended_action(probability, request()).lower()
            assert not any(word in action for word in ("cancel", "refuse", "deny", "deprioriti"))


class TestModelLoading:

    def test_a_missing_artifact_falls_back_instead_of_failing(self):
        model = load_model("no-such-directory")
        assert model.available is False
        value, _, fallback_used, _ = score(request(), model)
        assert fallback_used is True and 0.0 <= value <= 1.0
