"""
Kafka choreography: react to CLAIM_SUBMITTED on claims.events, fetch the photos
from claim-service, run triage, publish ASSESSMENT_COMPLETED on assessment.events.

Delivery semantics: the offset is committed only after the produce is flushed,
so a crash mid-way redelivers the claim event and we assess again. That is
at-least-once; claim-service deduplicates on eventId and ignores late results
for claims that already moved on. There is no outbox here because this service
owns no database — the trade-off is documented in the README.
"""
from __future__ import annotations

import json
import logging
import os
import threading
import uuid
from datetime import datetime, timezone
from decimal import Decimal
from typing import Callable

from app import model

log = logging.getLogger("assessment.consumer")

CLAIMS_TOPIC = os.getenv("CLAIMS_TOPIC", "claims.events")
ASSESSMENT_TOPIC = os.getenv("ASSESSMENT_TOPIC", "assessment.events")
GROUP_ID = os.getenv("KAFKA_GROUP_ID", "assessment-service")
CLAIM_SERVICE_URL = os.getenv("CLAIM_SERVICE_URL", "http://localhost:8080")

FetchPhotos = Callable[[str, list[str]], list[bytes]]
Produce = Callable[[str, str, dict, dict], None]   # topic, key, payload, headers


def build_assessment_event(claim_event: dict, fetch_photos: FetchPhotos) -> dict | None:
    """Pure function: claim event in, assessment event out (None if not our business)."""
    if claim_event.get("eventType") != "CLAIM_SUBMITTED":
        return None
    claim = claim_event["claim"]
    claim_id = claim_event["claimId"]
    photos = fetch_photos(claim_id, claim.get("photoIds") or [])
    amount = claim.get("estimatedAmount")
    result = model.assess(claim["description"], Decimal(str(amount)) if amount is not None else None, photos)
    return {
        "eventId": str(uuid.uuid4()),
        "eventType": "ASSESSMENT_COMPLETED",
        "claimId": claim_id,
        "causationEventId": claim_event.get("eventId"),
        "severity": result.severity.value,
        "assessedAmount": str(result.assessed_amount),
        "provider": result.provider,
        "modelVersion": result.model_version,
        "score": result.score,
        "explanation": result.explanation,
        "photosAnalysed": result.image_signal.images if result.image_signal else 0,
        "occurredAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
    }


def http_photo_fetcher(base_url: str) -> FetchPhotos:
    import httpx

    def fetch(claim_id: str, photo_ids: list[str]) -> list[bytes]:
        out: list[bytes] = []
        with httpx.Client(timeout=10.0) as client:
            for pid in photo_ids:
                r = client.get(f"{base_url}/api/v1/claims/{claim_id}/photos/{pid}")
                r.raise_for_status()
                out.append(r.content)
        return out

    return fetch


def run(bootstrap: str, stop: threading.Event) -> None:
    from confluent_kafka import Consumer, Producer

    consumer = Consumer({"bootstrap.servers": bootstrap, "group.id": GROUP_ID,
                         "auto.offset.reset": "earliest", "enable.auto.commit": False})
    producer = Producer({"bootstrap.servers": bootstrap, "enable.idempotence": True})
    consumer.subscribe([CLAIMS_TOPIC])
    fetch = http_photo_fetcher(CLAIM_SERVICE_URL)
    log.info("consuming %s -> producing %s (claim-service at %s)", CLAIMS_TOPIC, ASSESSMENT_TOPIC, CLAIM_SERVICE_URL)
    while not stop.is_set():
        msg = consumer.poll(1.0)
        if msg is None:
            continue
        if msg.error():
            log.error("kafka error: %s", msg.error())
            continue
        try:
            event = build_assessment_event(json.loads(msg.value()), fetch)
            if event is not None:
                headers = [(k, v) for k, v in (msg.headers() or []) if k in ("traceparent", "tracestate")]
                producer.produce(ASSESSMENT_TOPIC, key=msg.key(), value=json.dumps(event).encode(), headers=headers)
                producer.flush(10)
                log.info("claim %s -> %s (%s)", event["claimId"], event["severity"], ", ".join(event["explanation"]) or "no evidence")
            consumer.commit(msg, asynchronous=False)
        except Exception:   # noqa: BLE001 — keep the loop alive; the record will be retried on next poll
            log.exception("failed to process record %s@%s; will retry", msg.partition(), msg.offset())
            consumer.seek(msg)   # type: ignore[arg-type]
    consumer.close()


def start_if_configured() -> threading.Event | None:
    bootstrap = os.getenv("KAFKA_BOOTSTRAP_SERVERS")
    if not bootstrap:
        log.info("KAFKA_BOOTSTRAP_SERVERS not set: HTTP-only mode")
        return None
    stop = threading.Event()
    threading.Thread(target=run, args=(bootstrap, stop), daemon=True, name="kafka-consumer").start()
    return stop
