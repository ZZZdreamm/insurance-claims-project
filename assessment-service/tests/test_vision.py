import io

import pytest
from PIL import Image

from app import vision


def _png(color) -> bytes:
    buf = io.BytesIO()
    Image.new("RGB", (64, 48), color).save(buf, format="PNG")
    return buf.getvalue()


@pytest.mark.skipif(not vision.available(), reason="ONNX model not present (models/mobilenetv2-7.onnx)")
def test_mobilenet_runs_and_is_deterministic():
    a = vision.analyse([_png((120, 120, 120))])
    b = vision.analyse([_png((120, 120, 120))])
    assert a == b
    assert 0.0 <= a.damage_score <= 1.0
    assert a.images == 1


def test_no_images_means_no_signal():
    assert vision.analyse([]) is None
