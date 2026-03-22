"""
SeaTraceSrv Python Client
"""

from .models import (
    # Geographic section
    Section,
    # LOD
    Lod,
    # Weather enrichment
    CurrentWeather,
    HourlyWeather,
    WeatherEnrichment,
    # Event payloads
    Event,
    EventPayload,
    VesselPosition,
    WeatherAlert,
    SeaPhenomenon,
    Incident,
    # REST models
    HealthResponse,
    SourceStatus,
    SnapshotRequest,
    SnapshotResponse,
)
from .client import SeaTraceClient, AsyncSeaTraceClient
from .realtime import RealtimeClient, stream_events

__all__ = [
    # Geographic section
    "Section",
    # LOD
    "Lod",
    # Weather enrichment
    "CurrentWeather",
    "HourlyWeather",
    "WeatherEnrichment",
    # Event payloads
    "Event",
    "EventPayload",
    "VesselPosition",
    "WeatherAlert",
    "SeaPhenomenon",
    "Incident",
    # REST models
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

__version__ = "0.0.1"
