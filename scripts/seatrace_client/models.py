"""
SeaTraceSrv API Models

Generated from api-contracts/openapi.yaml
"""

from dataclasses import dataclass, field
from enum import Enum
from typing import Optional, Union
from datetime import datetime


class HealthStatus(str, Enum):
    """Health status enum."""
    OK = "Ok"
    DEGRADED = "Degraded"
    DOWN = "Down"


@dataclass
class HealthResponse:
    """Health check response."""
    status: str
    components: dict[str, str]

    @classmethod
    def from_dict(cls, data: dict) -> "HealthResponse":
        return cls(
            status=data["status"],
            components=data.get("components", {}),
        )


@dataclass
class SourceStatus:
    """Data source status."""
    id: str
    health: HealthStatus
    quality_score: float
    active: bool

    @classmethod
    def from_dict(cls, data: dict) -> "SourceStatus":
        return cls(
            id=data["id"],
            health=HealthStatus(data["health"]),
            quality_score=data["quality_score"],
            active=data["active"],
        )


@dataclass
class VesselPosition:
    """Vessel position event payload."""
    type: str
    mmsi: int
    lat: float
    lon: float
    sog: Optional[float] = None  # Speed Over Ground (knots)
    cog: Optional[float] = None  # Course Over Ground (degrees)

    @classmethod
    def from_dict(cls, data: dict) -> "VesselPosition":
        return cls(
            type=data["type"],
            mmsi=data["mmsi"],
            lat=data["lat"],
            lon=data["lon"],
            sog=data.get("sog"),
            cog=data.get("cog"),
        )

    def __str__(self) -> str:
        parts = [f"MMSI={self.mmsi}", f"pos=({self.lat:.4f}, {self.lon:.4f})"]
        if self.sog is not None:
            parts.append(f"sog={self.sog:.1f}kn")
        if self.cog is not None:
            parts.append(f"cog={self.cog:.1f}°")
        return f"VesselPosition({', '.join(parts)})"


@dataclass
class WeatherAlert:
    """Weather alert event payload."""
    type: str
    kind: str
    severity: str
    polygon: list[list[float]]

    @classmethod
    def from_dict(cls, data: dict) -> "WeatherAlert":
        return cls(
            type=data["type"],
            kind=data["kind"],
            severity=data["severity"],
            polygon=data["polygon"],
        )

    def __str__(self) -> str:
        return f"WeatherAlert(kind={self.kind}, severity={self.severity})"


@dataclass
class SeaPhenomenon:
    """Sea phenomenon event payload."""
    type: str
    kind: str
    lat: float
    lon: float
    evidence: Optional[str] = None

    @classmethod
    def from_dict(cls, data: dict) -> "SeaPhenomenon":
        return cls(
            type=data["type"],
            kind=data["kind"],
            lat=data["lat"],
            lon=data["lon"],
            evidence=data.get("evidence"),
        )

    def __str__(self) -> str:
        return f"SeaPhenomenon(kind={self.kind}, pos=({self.lat:.4f}, {self.lon:.4f}))"


@dataclass
class Incident:
    """Incident event payload."""
    type: str
    kind: str
    lat: float
    lon: float
    vessel_mmsi: Optional[int] = None

    @classmethod
    def from_dict(cls, data: dict) -> "Incident":
        return cls(
            type=data["type"],
            kind=data["kind"],
            lat=data["lat"],
            lon=data["lon"],
            vessel_mmsi=data.get("vessel_mmsi"),
        )

    def __str__(self) -> str:
        parts = [f"kind={self.kind}", f"pos=({self.lat:.4f}, {self.lon:.4f})"]
        if self.vessel_mmsi:
            parts.append(f"mmsi={self.vessel_mmsi}")
        return f"Incident({', '.join(parts)})"


# Union type for all event payloads
EventPayload = Union[VesselPosition, WeatherAlert, SeaPhenomenon, Incident]


def parse_payload(data: dict) -> EventPayload:
    """Parse event payload based on discriminator 'type' field."""
    payload_type = data.get("type")

    if payload_type == "VesselPosition":
        return VesselPosition.from_dict(data)
    elif payload_type == "WeatherAlert":
        return WeatherAlert.from_dict(data)
    elif payload_type == "SeaPhenomenon":
        return SeaPhenomenon.from_dict(data)
    elif payload_type == "Incident":
        return Incident.from_dict(data)
    else:
        raise ValueError(f"Unknown payload type: {payload_type}")


@dataclass
class Event:
    """Main event structure."""
    event_id: str
    h3_index: int
    timestamp: int  # Unix timestamp in milliseconds
    source: str
    confidence: float
    payload: EventPayload

    @classmethod
    def from_dict(cls, data: dict) -> "Event":
        return cls(
            event_id=data["event_id"],
            h3_index=data["h3_index"],
            timestamp=data["timestamp"],
            source=data["source"],
            confidence=data["confidence"],
            payload=parse_payload(data["payload"]),
        )

    @property
    def datetime(self) -> datetime:
        """Convert timestamp to datetime."""
        return datetime.fromtimestamp(self.timestamp / 1000)

    def __str__(self) -> str:
        return f"Event({self.datetime.strftime('%H:%M:%S')} {self.payload})"


@dataclass
class SnapshotRequest:
    """Request for pulling state snapshot."""
    h3_cells: list[int]
    categories: Optional[list[str]] = None

    def to_dict(self) -> dict:
        result = {"h3_cells": self.h3_cells}
        if self.categories:
            result["categories"] = self.categories
        return result


@dataclass
class SnapshotResponse:
    """Response containing state snapshot."""
    events: list[Event]

    @classmethod
    def from_dict(cls, data: dict) -> "SnapshotResponse":
        return cls(
            events=[Event.from_dict(e) for e in data.get("events", [])]
        )
