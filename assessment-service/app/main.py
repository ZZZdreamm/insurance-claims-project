import base64
from contextlib import asynccontextmanager
from decimal import Decimal

from fastapi import FastAPI
from pydantic import BaseModel, Field

from app import consumer, model, observability, vision


@asynccontextmanager
async def lifespan(app: FastAPI):
    stop = consumer.start_if_configured()
    yield
    if stop is not None:
        stop.set()


app = FastAPI(title="assessment-service", version=model.MODEL_VERSION, lifespan=lifespan)
observability.install(app)


class AssessRequest(BaseModel):
    claimId: str
    description: str = Field(min_length=1)
    estimatedAmount: Decimal | None = Field(default=None, ge=0)
    photosBase64: list[str] = Field(default_factory=list, max_length=10)


class ImageSignalOut(BaseModel):
    damageScore: float
    wreckProb: float
    vehicleProb: float
    images: int


class AssessResponse(BaseModel):
    claimId: str
    severity: model.Severity
    assessedAmount: Decimal
    provider: str = "assessment-service"
    modelVersion: str = model.MODEL_VERSION
    score: float
    matchedTerms: list[str]
    explanation: list[str]
    imageSignal: ImageSignalOut | None = None


@app.get("/health")
def health() -> dict:
    return {"status": "UP", "modelVersion": model.MODEL_VERSION, "visionModel": vision.available()}


@app.post("/assess", response_model=AssessResponse)
def assess(req: AssessRequest) -> AssessResponse:
    images = [base64.b64decode(p) for p in req.photosBase64]
    result = model.assess(req.description, req.estimatedAmount, images)
    observability.REQUESTS.labels(severity=result.severity.value).inc()
    sig = result.image_signal
    return AssessResponse(
        claimId=req.claimId, severity=result.severity, assessedAmount=result.assessed_amount,
        score=result.score, matchedTerms=result.matched_terms, explanation=result.explanation,
        imageSignal=ImageSignalOut(damageScore=sig.damage_score, wreckProb=sig.wreck_prob,
                                   vehicleProb=sig.vehicle_prob, images=sig.images) if sig else None,
    )
