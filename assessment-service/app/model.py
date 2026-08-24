"""
Damage-severity triage model.

This is a weighted keyword model with an amount prior — small, deterministic and
dependency-free, which is what makes the demo runnable on any laptop and the
integration tests reproducible. It sits behind the same `assess()` contract an
ONNX image classifier would; swapping in a real vision model changes this file,
not the service or its callers.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from decimal import Decimal, ROUND_HALF_UP
from enum import Enum

MODEL_VERSION = "kw-2"


class Severity(str, Enum):
    MINOR = "MINOR"
    MODERATE = "MODERATE"
    SEVERE = "SEVERE"


# term -> evidence weight (positive = more severe)
WEIGHTS: dict[str, float] = {
    "scratch": -1.0, "scuff": -1.0, "chip": -0.8, "dent": -0.3, "mirror": -0.6, "paint": -0.5,
    "bumper": 0.2, "tail light": 0.3, "headlight": 0.6, "windscreen": 0.8, "windshield": 0.8,
    "door": 0.7, "bonnet": 0.9, "hood": 0.9, "wheel": 0.8, "axle": 1.5, "suspension": 1.4,
    "airbag": 2.5, "frame": 2.5, "chassis": 2.5, "engine": 2.2, "fire": 3.0, "flood": 3.0,
    "rolled": 3.0, "rollover": 3.0, "total loss": 4.0, "write-off": 4.0, "pile-up": 2.0,
    "not drivable": 2.0, "undrivable": 2.0, "towed": 1.5,
}

BAND_FLOOR = {Severity.MINOR: Decimal("300"), Severity.MODERATE: Decimal("1500"), Severity.SEVERE: Decimal("8000")}


@dataclass(frozen=True)
class Assessment:
    severity: Severity
    assessed_amount: Decimal
    score: float
    matched_terms: list[str]


def _normalise(text: str) -> str:
    return re.sub(r"\s+", " ", text.lower())


def assess(description: str, estimated_amount: Decimal | None) -> Assessment:
    text = _normalise(description)
    matched = [term for term in WEIGHTS if term in text]
    score = sum(WEIGHTS[t] for t in matched)

    estimate = estimated_amount if estimated_amount is not None else Decimal("0")
    # amount prior: the policyholder's own estimate is weak but informative evidence
    if estimate >= 10_000:
        score += 2.0
    elif estimate >= 2_500:
        score += 1.5
    elif estimate > 0 and estimate < 500:
        score -= 0.5

    if score >= 2.0:
        severity = Severity.SEVERE
    elif score >= 0.5:
        severity = Severity.MODERATE
    else:
        severity = Severity.MINOR

    amount = max(estimate, BAND_FLOOR[severity]).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
    return Assessment(severity=severity, assessed_amount=amount, score=round(score, 2), matched_terms=matched)
