"""
SeaTraceSrv Python Client

Auto-generated from api-contracts/openapi.yaml
"""

from .models import (
    Event,
    EventPayload,
    VesselPosition,
    WeatherAlert,
    SeaPhenomenon,
    Incident,
    HealthResponse,
    SourceStatus,
    SnapshotRequest,
    SnapshotResponse,
)
from .client import SeaTraceClient, AsyncSeaTraceClient
from .realtime import RealtimeClient, stream_events

__all__ = [
    # Models
    "Event",
    "EventPayload",
    "VesselPosition",
    "WeatherAlert",
    "SeaPhenomenon",
    "Incident",
    "HealthResponse",
    "SourceStatus",
    "SnapshotRequest",
    "SnapshotResponse",
    # Clients
    "SeaTraceClient",
    "AsyncSeaTraceClient",
    "RealtimeClient",
    "stream_events",
]

__version__ = "1.0.0"
