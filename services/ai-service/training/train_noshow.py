"""
Trains the no-show risk model and writes it to disk.

Run: python -m training.train_noshow

Gradient boosting on the synthetic history. The model is calibrated so the score can be read as a
probability rather than a rank - a clinic acting on "38% likely to miss" needs that number to
mean something, and an uncalibrated tree ensemble's raw output does not.
"""

from __future__ import annotations

import json
import pathlib
import sys

import joblib
import numpy as np
from sklearn.calibration import CalibratedClassifierCV
from sklearn.ensemble import HistGradientBoostingClassifier
from sklearn.metrics import brier_score_loss, roc_auc_score
from sklearn.model_selection import train_test_split

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from training.generate_noshow_data import FEATURE_NAMES, generate

MODEL_DIR = pathlib.Path(__file__).resolve().parents[1] / "models"
MODEL_PATH = MODEL_DIR / "noshow.joblib"
METRICS_PATH = MODEL_DIR / "noshow-metrics.json"


def main() -> None:
    features, labels = generate()
    x_train, x_test, y_train, y_test = train_test_split(
        features, labels, test_size=0.25, random_state=20260901, stratify=labels
    )

    base = HistGradientBoostingClassifier(
        max_iter=250, learning_rate=0.06, max_depth=6, min_samples_leaf=40,
        l2_regularization=1.0, random_state=20260901,
    )
    # Isotonic calibration on held-out folds: the score is presented to clinic staff as a
    # probability, so it has to behave like one.
    model = CalibratedClassifierCV(base, method="isotonic", cv=4)
    model.fit(x_train, y_train)

    predicted = model.predict_proba(x_test)[:, 1]
    metrics = {
        "roc_auc": round(float(roc_auc_score(y_test, predicted)), 4),
        "brier_score": round(float(brier_score_loss(y_test, predicted)), 4),
        "base_rate": round(float(np.mean(labels)), 4),
        "n_train": len(x_train),
        "n_test": len(x_test),
        "features": FEATURE_NAMES,
        "data": "synthetic - see training/generate_noshow_data.py",
    }

    MODEL_DIR.mkdir(parents=True, exist_ok=True)
    joblib.dump({"model": model, "features": FEATURE_NAMES}, MODEL_PATH)
    METRICS_PATH.write_text(json.dumps(metrics, indent=2) + "\n")

    print(f"wrote {MODEL_PATH}")
    print(json.dumps(metrics, indent=2))


if __name__ == "__main__":
    main()
