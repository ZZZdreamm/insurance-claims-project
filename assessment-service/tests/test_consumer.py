import json
import uuid

from app.consumer import build_assessment_event


def _claim_event(description, photo_ids=None, amount=800):
    return {
        "eventId": str(uuid.uuid4()), "eventType": "CLAIM_SUBMITTED", "claimId": str(uuid.uuid4()),
        "occurredAt": "2026-08-24T10:00:00Z",
        "claim": {"claimNumber": "CLM-1", "description": description, "estimatedAmount": amount, "photoIds": photo_ids or []},
    }


def test_builds_assessment_completed_from_claim_submitted():
    fetched = []

    def fetch(claim_id, ids):
        fetched.append((claim_id, ids))
        return []

    src = _claim_event("Airbag deployed, frame bent", ["p1", "p2"])
    out = build_assessment_event(src, fetch)

    assert fetched == [(src["claimId"], ["p1", "p2"])]
    assert out["eventType"] == "ASSESSMENT_COMPLETED"
    assert out["claimId"] == src["claimId"]
    assert out["causationEventId"] == src["eventId"]
    assert out["severity"] == "SEVERE"
    assert out["assessedAmount"] == "8000.00"
    assert out["provider"] == "assessment-service"
    json.dumps(out)   # serialisable


def test_ignores_other_event_types():
    e = _claim_event("x"); e["eventType"] = "CLAIM_APPROVED"
    assert build_assessment_event(e, lambda c, i: []) is None
