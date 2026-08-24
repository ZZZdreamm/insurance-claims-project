from decimal import Decimal

from fastapi import FastAPI
from pydantic import BaseModel, Field

from app import model, observability

app = FastAPI(title="assessment-service", version=model.MODEL_VERSION)
observability.install(app)


class AssessRequest(BaseModel):
    claimId: str
    description: str = Field(min_length=1)
    estimatedAmount: Decimal | None = Field(default=None, ge=0)


class AssessResponse(BaseModel):
    claimId: str
    severity: model.Severity
    assessedAmount: Decimal
    provider: str = "assessment-service"
    modelVersion: str = model.MODEL_VERSION
    score: float
    matchedTerms: list[str]


@app.get("/health")
def health() -> dict:
    return {"status": "UP", "modelVersion": model.MODEL_VERSION}


@app.post("/assess", response_model=AssessResponse)
def assess(req: AssessRequest) -> AssessResponse:
    result = model.assess(req.description, req.estimatedAmount)
    observability.REQUESTS.labels(severity=result.severity.value).inc()
    return AssessResponse(
        claimId=req.claimId,
        severity=result.severity,
        assessedAmount=result.assessed_amount,
        score=result.score,
        matchedTerms=result.matched_terms,
    )
