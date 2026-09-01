"""
Appointment no-show risk.

Loads the trained model if it is on disk, and otherwise scores with the same logistic form the
training data was generated from. Either way the caller gets a calibrated probability and the
factors behind it: a bare number attached to a patient's name, with no explanation, is not
something a clinic should act on.
"""

from __future__ import annotations

import logging
import math
import pathlib
from dataclasses import dataclass

import numpy as np

from app.schemas import NoShowRequest, RiskDriver

logger = logging.getLogger(__name__)

#: Band thresholds. Chosen so HIGH is rare enough that acting on it (a phone call, a
#: double-booked slot) stays affordable for a real clinic.
_MEDIUM_THRESHOLD = 0.20
_HIGH_THRESHOLD = 0.40

#: The fallback's coefficients, matching training/generate_noshow_data.py. Keeping them here means
#: the service still gives sensible, explainable answers before anyone has trained a model.
_INTERCEPT = -2.35
_WEIGHTS: dict[str, float] = {
    "lead_time_days": 0.021,
    "prior_no_show_rate": 2.20,
    "is_first_visit": 0.42,
    "has_reminder_contact": -0.65,
    "distance_km": 0.010,
    "age_delta": -0.011,
    "early_clinic": 0.16,
    "monday": 0.14,
    "weekend": 0.10,
    "is_urgent": -0.85,
}

_EXPLANATIONS: dict[str, str] = {
    "lead_time_days": "Booked {lead} day(s) ahead",
    "prior_no_show_rate": "Missed {missed} of {total} previous appointment(s)",
    "is_first_visit": "First visit to this clinic",
    "has_reminder_contact": "Reachable for a reminder",
    "distance_km": "Travels {distance} km",
    "age_delta": "Age {age}",
    "early_clinic": "Early clinic slot ({hour}:00)",
    "monday": "Monday appointment",
    "weekend": "Weekend appointment",
    "is_urgent": "Marked {priority}",
}


@dataclass
class NoShowModel:
    """The trained model, or nothing if none has been built yet."""

    estimator: object | None
    feature_names: list[str] | None

    @property
    def available(self) -> bool:
        return self.estimator is not None


def load_model(model_dir: str) -> NoShowModel:
    """Loads the trained model, tolerating its absence — the fallback covers that case."""
    path = pathlib.Path(model_dir) / "noshow.joblib"
    if not path.exists():
        logger.info("No trained no-show model at %s; using the built-in logistic fallback", path)
        return NoShowModel(estimator=None, feature_names=None)
    try:
        import joblib

        bundle = joblib.load(path)
        logger.info("Loaded no-show model from %s", path)
        return NoShowModel(estimator=bundle["model"], feature_names=bundle["features"])
    except Exception:
        # A corrupt or version-mismatched artifact must not stop the service from booting;
        # the logistic fallback still answers correctly.
        logger.exception("Could not load %s; falling back to the logistic model", path)
        return NoShowModel(estimator=None, feature_names=None)


def _feature_vector(request: NoShowRequest) -> np.ndarray:
    prior_rate = (
        request.previous_no_shows / request.previous_appointments
        if request.previous_appointments > 0 else 0.0
    )
    return np.array([[
        float(request.lead_time_days),
        float(request.patient_age),
        float(request.previous_appointments),
        float(request.previous_no_shows),
        float(prior_rate),
        float(request.hour_of_day),
        float(request.day_of_week),
        1.0 if request.is_first_visit or request.previous_appointments == 0 else 0.0,
        1.0 if request.has_reminder_contact else 0.0,
        float(request.distance_km),
        1.0 if request.priority.upper() in {"URGENT", "STAT"} else 0.0,
    ]])


def _terms(request: NoShowRequest) -> dict[str, float]:
    """The fallback's per-factor contributions to the log-odds."""
    prior_rate = (
        request.previous_no_shows / request.previous_appointments
        if request.previous_appointments > 0 else 0.0
    )
    first_visit = request.is_first_visit or request.previous_appointments == 0
    return {
        "lead_time_days": _WEIGHTS["lead_time_days"] * request.lead_time_days,
        "prior_no_show_rate": _WEIGHTS["prior_no_show_rate"] * prior_rate,
        "is_first_visit": _WEIGHTS["is_first_visit"] * (1.0 if first_visit else 0.0),
        "has_reminder_contact": _WEIGHTS["has_reminder_contact"] * (
            1.0 if request.has_reminder_contact else 0.0),
        "distance_km": _WEIGHTS["distance_km"] * request.distance_km,
        "age_delta": _WEIGHTS["age_delta"] * (request.patient_age - 42),
        "early_clinic": _WEIGHTS["early_clinic"] * (1.0 if request.hour_of_day < 9 else 0.0),
        "monday": _WEIGHTS["monday"] * (1.0 if request.day_of_week == 0 else 0.0),
        "weekend": _WEIGHTS["weekend"] * (1.0 if request.day_of_week >= 5 else 0.0),
        "is_urgent": _WEIGHTS["is_urgent"] * (
            1.0 if request.priority.upper() in {"URGENT", "STAT"} else 0.0),
    }


def _describe(feature: str, request: NoShowRequest) -> str:
    template = _EXPLANATIONS.get(feature, feature)
    return template.format(
        lead=request.lead_time_days,
        missed=request.previous_no_shows,
        total=request.previous_appointments,
        distance=round(request.distance_km, 1),
        age=request.patient_age,
        hour=request.hour_of_day,
        priority=request.priority.upper(),
    )


def band(score: float) -> str:
    if score >= _HIGH_THRESHOLD:
        return "HIGH"
    if score >= _MEDIUM_THRESHOLD:
        return "MEDIUM"
    return "LOW"


def recommended_action(score: float, request: NoShowRequest) -> str:
    """What a clinic can actually do about the score, rather than the score alone."""
    if score >= _HIGH_THRESHOLD:
        if not request.has_reminder_contact:
            return ("High risk with no reminder contact on file: capture a phone number, and "
                    "consider overbooking this slot.")
        return ("High risk: send a reminder 48 and 24 hours ahead, and consider a confirmation "
                "call or overbooking the slot.")
    if score >= _MEDIUM_THRESHOLD:
        return "Moderate risk: an SMS reminder 24 hours ahead should be enough."
    return "Low risk: standard reminder."


def score(request: NoShowRequest, model: NoShowModel) -> tuple[float, list[RiskDriver], bool, float]:
    """
    Scores one appointment.

    @return (probability, drivers, fallback_used, confidence)
    """
    terms = _terms(request)
    # Drivers come from the interpretable form in both paths. The trained model provides the
    # probability; the linear terms explain the direction of each factor, which is what a
    # scheduler needs and what a tree ensemble cannot state simply.
    drivers = [
        RiskDriver(
            feature=feature,
            contribution=round(contribution, 4),
            detail=_describe(feature, request),
        )
        for feature, contribution in sorted(terms.items(), key=lambda item: -abs(item[1]))
        if abs(contribution) > 0.01
    ][:6]

    if model.available:
        probability = float(model.estimator.predict_proba(_feature_vector(request))[0][1])
        # More prior history means the strongest feature is better grounded.
        confidence = round(min(0.65 + 0.02 * min(request.previous_appointments, 10), 0.9), 2)
        return round(probability, 4), drivers, False, confidence

    logit = _INTERCEPT + sum(terms.values())
    probability = 1.0 / (1.0 + math.exp(-logit))
    return round(probability, 4), drivers, True, 0.55
