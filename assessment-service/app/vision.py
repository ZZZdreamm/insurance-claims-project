"""
Image signal: MobileNetV2 (ImageNet, ONNX Runtime, CPU, ~15 ms per image).

Honest framing: there is no free labelled car-damage dataset shipped with this
repo, so the network is not fine-tuned. Instead we read the ImageNet head as a
zero-shot damage detector: probability mass on "wreck" versus mass on intact
vehicle classes (sports car, minivan, pickup, ...). That gives a real,
deterministic image-derived score. Fine-tuning a head on e.g. the Roboflow
car-damage set is a drop-in change to this file only.
"""
from __future__ import annotations

import io
import os
from dataclasses import dataclass
from functools import lru_cache

import numpy as np

MODEL_PATH = os.getenv("MODEL_PATH", os.path.join(os.path.dirname(__file__), "..", "models", "mobilenetv2-7.onnx"))
MODEL_VERSION = "mobilenetv2-imagenet-zeroshot-1"

WRECK = 913                       # ImageNet "wreck"
TOW_TRUCK = 864
VEHICLE = [407, 436, 468, 511, 609, 627, 656, 661, 675, 717, 751, 817, 867]   # ambulance ... trailer truck

MEAN = np.array([0.485, 0.456, 0.406], dtype=np.float32)
STD = np.array([0.229, 0.224, 0.225], dtype=np.float32)


@dataclass(frozen=True)
class ImageSignal:
    damage_score: float       # 0..1, share of probability mass on damage vs intact-vehicle classes
    wreck_prob: float
    vehicle_prob: float
    images: int


@lru_cache(maxsize=1)
def _session():
    if not os.path.exists(MODEL_PATH):
        return None
    import onnxruntime as ort
    opts = ort.SessionOptions()
    opts.intra_op_num_threads = 1
    return ort.InferenceSession(MODEL_PATH, opts, providers=["CPUExecutionProvider"])


def available() -> bool:
    return _session() is not None


def _preprocess(raw: bytes) -> np.ndarray:
    from PIL import Image
    img = Image.open(io.BytesIO(raw)).convert("RGB").resize((224, 224))
    x = (np.asarray(img, dtype=np.float32) / 255.0 - MEAN) / STD
    return np.transpose(x, (2, 0, 1))[None, ...]   # NCHW


def _softmax(z: np.ndarray) -> np.ndarray:
    z = z - z.max()
    e = np.exp(z)
    return e / e.sum()


def analyse(images: list[bytes]) -> ImageSignal | None:
    session = _session()
    if session is None or not images:
        return None
    wreck = vehicle = 0.0
    for raw in images:
        logits = session.run(None, {session.get_inputs()[0].name: _preprocess(raw)})[0][0]
        p = _softmax(logits)
        wreck += float(p[WRECK] + 0.5 * p[TOW_TRUCK])
        vehicle += float(p[VEHICLE].sum())
    n = len(images)
    wreck, vehicle = wreck / n, vehicle / n
    score = wreck / (wreck + vehicle + 1e-6)
    return ImageSignal(damage_score=round(score, 4), wreck_prob=round(wreck, 4), vehicle_prob=round(vehicle, 4), images=n)
