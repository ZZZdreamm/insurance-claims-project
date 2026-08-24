"""
Damage-severity triage = text evidence + amount prior + image evidence (MobileNet).

Text and amount are cheap and always available; the image signal (app/vision.py)
is added when photos are attached and the ONNX model is present. Deterministic
by construction, so the same claim always triages the same way.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from decimal import Decimal, ROUND_HALF_UP
from enum import Enum

from app import vision

MODEL_VERSION = f"kw-2+{vision.MODEL_VERSION}"


class Severity(str, Enum):
    MINOR = "MINOR"
    MODERATE = "MODERATE"
    SEVERE = "SEVERE"


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
    image_signal: vision.ImageSignal | None = None
    provider: str = "assessment-service"
    model_version: str = MODEL_VERSION
    explanation: list[str] = field(default_factory=list)


def _normalise(text: str) -> str:
    return re.sub(r"\s+", " ", text.lower())


def assess(description: str, estimated_amount: Decimal | None, images: list[bytes] | None = None) -> Assessment:
    text = _normalise(description)
    matched = [term for term in WEIGHTS if term in text]
    score = sum(WEIGHTS[t] for t in matched)
    why = [f"text:{t}({WEIGHTS[t]:+.1f})" for t in matched]

    estimate = estimated_amount if estimated_amount is not None else Decimal("0")
    if estimate >= 10_000:
        score += 2.0; why.append("estimate>=10000(+2.0)")
    elif estimate >= 2_500:
        score += 1.5; why.append("estimate>=2500(+1.5)")
    elif 0 < estimate < 500:
        score -= 0.5; why.append("estimate<500(-0.5)")

    signal = vision.analyse(images or [])
    if signal is not None:
        # image evidence: strong "wreck" mass dominates the text; a clearly intact car pulls the score down
        if signal.damage_score >= 0.5:
            score += 3.0; why.append(f"image:wreck({signal.damage_score:.2f})(+3.0)")
        elif signal.damage_score >= 0.2:
            score += 1.5; why.append(f"image:damage({signal.damage_score:.2f})(+1.5)")
        elif signal.vehicle_prob >= 0.3:
            score -= 0.5; why.append(f"image:intact({signal.vehicle_prob:.2f})(-0.5)")

    if score >= 2.0:
        severity = Severity.SEVERE
    elif score >= 0.5:
        severity = Severity.MODERATE
    else:
        severity = Severity.MINOR

    amount = max(estimate, BAND_FLOOR[severity]).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
    return Assessment(severity=severity, assessed_amount=amount, score=round(score, 2), matched_terms=matched,
                      image_signal=signal, explanation=why)
