from decimal import Decimal

import pytest

from app.model import Severity, assess


@pytest.mark.parametrize(
    "description,estimate,severity,amount",
    [
        ("Scratched rear bumper in a car park", Decimal("400"), Severity.MINOR, Decimal("400.00")),
        ("Scratched rear bumper in a car park", None, Severity.MINOR, Decimal("300.00")),
        ("Cracked windscreen from a stone", Decimal("800"), Severity.MODERATE, Decimal("1500.00")),
        ("Minor scuff", Decimal("3000"), Severity.MODERATE, Decimal("3000.00")),
        ("Engine bay fire after a collision", Decimal("500"), Severity.SEVERE, Decimal("8000.00")),
        ("Total loss after a motorway pile-up", Decimal("70000"), Severity.SEVERE, Decimal("70000.00")),
    ],
)
def test_classification(description, estimate, severity, amount):
    a = assess(description, estimate)
    assert a.severity == severity
    assert a.assessed_amount == amount


def test_is_deterministic():
    assert assess("door dented, headlight broken", Decimal("900")) == assess("door dented, headlight broken", Decimal("900"))
