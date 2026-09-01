"""
Generates the synthetic appointment history the no-show model trains on.

Synthetic on purpose: a no-show model trained on real attendance data learns the access barriers
of the population it came from, and shipping that in a repository would both leak patient
behaviour and hard-code one hospital's inequities into everyone else's scheduling.

The generative process below encodes relationships that are well documented in the scheduling
literature - longer lead times, prior no-shows, first visits and distance all raise
non-attendance - so the trained model is honest about being a demonstration of the mechanism
rather than a validated clinical instrument.
"""

from __future__ import annotations

import numpy as np

FEATURE_NAMES: list[str] = [
    "lead_time_days",
    "patient_age",
    "previous_appointments",
    "previous_no_shows",
    "prior_no_show_rate",
    "hour_of_day",
    "day_of_week",
    "is_first_visit",
    "has_reminder_contact",
    "distance_km",
    "is_urgent",
]


def generate(n_samples: int = 12_000, seed: int = 20260901) -> tuple[np.ndarray, np.ndarray]:
    """Returns (features, labels) where a label of 1 means the patient did not attend."""
    rng = np.random.default_rng(seed)

    lead_time = rng.gamma(shape=2.0, scale=7.0, size=n_samples).clip(0, 180)
    age = rng.normal(42, 19, n_samples).clip(0, 98)
    previous_appointments = rng.poisson(4.0, n_samples).clip(0, 60)
    # Prior no-shows can never exceed prior appointments.
    prior_rate_latent = rng.beta(1.4, 6.0, n_samples)
    previous_no_shows = rng.binomial(previous_appointments.astype(int), prior_rate_latent)
    prior_no_show_rate = np.where(
        previous_appointments > 0, previous_no_shows / np.maximum(previous_appointments, 1), 0.0
    )
    hour = rng.integers(7, 20, n_samples)
    day_of_week = rng.integers(0, 7, n_samples)
    is_first_visit = (previous_appointments == 0).astype(float)
    has_reminder = rng.binomial(1, 0.82, n_samples).astype(float)
    distance = rng.gamma(shape=1.8, scale=6.0, size=n_samples).clip(0, 300)
    is_urgent = rng.binomial(1, 0.18, n_samples).astype(float)

    # Log-odds of not attending. Coefficients are chosen to reproduce the direction and rough
    # magnitude of published effects, and an overall no-show rate near a realistic 18%.
    logit = (
        -2.35
        + 0.021 * lead_time                 # a distant appointment is easier to forget
        + 2.20 * prior_no_show_rate         # past non-attendance is the strongest single signal
        + 0.42 * is_first_visit             # no relationship with the clinic yet
        - 0.65 * has_reminder               # a reachable patient gets reminded
        + 0.010 * distance                  # travel burden
        - 0.011 * (age - 42)                # older patients attend more reliably
        + 0.16 * (hour < 9)                 # early clinics are missed more often
        + 0.14 * (day_of_week == 0)         # Monday
        + 0.10 * (day_of_week >= 5)         # weekend clinics
        - 0.85 * is_urgent                  # an urgent appointment is kept
    )
    probability = 1.0 / (1.0 + np.exp(-logit))
    labels = rng.binomial(1, probability)

    features = np.column_stack([
        lead_time, age, previous_appointments, previous_no_shows, prior_no_show_rate,
        hour, day_of_week, is_first_visit, has_reminder, distance, is_urgent,
    ])
    return features, labels


if __name__ == "__main__":
    features, labels = generate()
    print(f"{features.shape[0]} samples, {features.shape[1]} features")
    print(f"no-show rate: {labels.mean():.1%}")
