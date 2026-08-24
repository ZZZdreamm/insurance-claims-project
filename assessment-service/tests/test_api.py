from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def test_health():
    r = client.get("/health")
    assert r.status_code == 200
    assert r.json()["status"] == "UP"


def test_assess_contract():
    r = client.post("/assess", json={"claimId": "abc", "description": "Airbag deployed, frame bent", "estimatedAmount": 500})
    assert r.status_code == 200
    body = r.json()
    assert body["severity"] == "SEVERE"
    assert body["assessedAmount"] == "8000.00" or float(body["assessedAmount"]) == 8000.0
    assert body["provider"] == "assessment-service"
    assert body["modelVersion"]
    assert "airbag" in body["matchedTerms"]


def test_validation():
    assert client.post("/assess", json={"claimId": "x", "description": ""}).status_code == 422
    assert client.post("/assess", json={"claimId": "x", "description": "ok", "estimatedAmount": -1}).status_code == 422


def test_metrics_exposed():
    client.post("/assess", json={"claimId": "m", "description": "scratch", "estimatedAmount": 100})
    r = client.get("/metrics")
    assert r.status_code == 200
    assert "assessment_requests_total" in r.text
