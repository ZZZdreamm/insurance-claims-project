"""Prometheus metrics + OpenTelemetry tracing. Tracing exports only when OTEL_EXPORTER_OTLP_ENDPOINT is set."""
import os
import time

from fastapi import FastAPI, Request
from prometheus_client import CONTENT_TYPE_LATEST, Counter, Histogram, generate_latest
from starlette.responses import Response

REQUESTS = Counter("assessment_requests_total", "Assessment requests", ["severity"])
LATENCY = Histogram("assessment_latency_seconds", "Model latency", buckets=(0.001, 0.005, 0.01, 0.05, 0.1, 0.5))


def install(app: FastAPI) -> None:
    @app.get("/metrics", include_in_schema=False)
    def metrics() -> Response:
        return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)

    @app.middleware("http")
    async def timing(request: Request, call_next):
        start = time.perf_counter()
        response = await call_next(request)
        if request.url.path == "/assess":
            LATENCY.observe(time.perf_counter() - start)
        return response

    endpoint = os.getenv("OTEL_EXPORTER_OTLP_ENDPOINT")
    if endpoint:
        from opentelemetry import trace
        from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
        from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor
        from opentelemetry.sdk.resources import Resource
        from opentelemetry.sdk.trace import TracerProvider
        from opentelemetry.sdk.trace.export import BatchSpanProcessor

        provider = TracerProvider(resource=Resource.create({"service.name": "assessment-service"}))
        provider.add_span_processor(BatchSpanProcessor(OTLPSpanExporter(endpoint=f"{endpoint}/v1/traces")))
        trace.set_tracer_provider(provider)
        # W3C traceparent from claim-service's WebClient is picked up here, so the claim's trace continues
        FastAPIInstrumentor.instrument_app(app)
