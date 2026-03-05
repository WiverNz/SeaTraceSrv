"""
SeaTraceSrv API Models

Mirrors api-contracts/openapi.yaml and api-contracts/asyncapi.yaml.
"""

from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum
from typing import Optional, Union


# ── Level of Detail ──────────────────────────────────────────────────────────

class Lod(str, Enum):
    """
    Detail level flags a client can request in a subscription.

    Multiple values may be combined. The server enriches each event with the
    requested data. Add new values here as the server gains new enrichment
    sources (water conditions, depth, currents, …).

    Usage:
        await client.subscribe(lod=[Lod.WEATHER_CURRENT])
        await client.subscribe(lod=[Lod.WEATHER_CURRENT, Lod.WEATHER_HOURLY])
    """
    VESSELS = "vessels"
    WEATHER_CURRENT = "weather_current"
    WEATHER_HOURLY = "weather_hourly"
    # Future enrichments — uncomment when server-side support is added:
    # WATER_CONDITIONS = "water_conditions"  # buoy / channel data
    # DEPTH = "depth"                        # bathymetric depth at position
    # WATER_CURRENTS = "water_currents"      # surface current vector


# ── Weather enrichment ────────────────────────────────────────────────────────

@dataclass
class CurrentWeather:
    """Current conditions at the event position (Open-Meteo)."""
    time: str
    temperature_2m: float    # °C
    wind_speed_10m: float    # km/h
    relative_humidity_2m: float  # %

    @classmethod
    def from_dict(cls, data: dict) -> "CurrentWeather":
        return cls(
            time=data["time"],
            temperature_2m=data["temperature_2m"],
            wind_speed_10m=data["wind_speed_10m"],
            relative_humidity_2m=data["relative_humidity_2m"],
        )

    def __str__(self) -> str:
        return (
            f"{self.temperature_2m:.1f}°C  "
            f"wind {self.wind_speed_10m:.1f} km/h  "
            f"rh {self.relative_humidity_2m:.0f}%"
        )


@dataclass
class HourlyWeather:
    """Hourly forecast for the next 24 hours (Open-Meteo)."""
    time: list[str]
    temperature_2m: list[float]
    wind_speed_10m: list[float]
    relative_humidity_2m: list[float]

    @classmethod
    def from_dict(cls, data: dict) -> "HourlyWeather":
        return cls(
            time=data["time"],
            temperature_2m=data["temperature_2m"],
            wind_speed_10m=data["wind_speed_10m"],
            relative_humidity_2m=data["relative_humidity_2m"],
        )

    def at_hour(self, index: int) -> str:
        """Return a summary string for a single forecast hour."""
        return (
            f"{self.time[index]}  "
            f"{self.temperature_2m[index]:.1f}°C  "
            f"{self.wind_speed_10m[index]:.1f} km/h"
        )


@dataclass
class WeatherEnrichment:
    """
    Weather data attached to an event when the client requests a weather LOD.

    Both fields are optional — `current` is present for WeatherCurrent LOD,
    `hourly` is present for WeatherHourly LOD.
    """
    current: Optional[CurrentWeather] = None
    hourly: Optional[HourlyWeather] = None

    @classmethod
    def from_dict(cls, data: dict) -> "WeatherEnrichment":
        return cls(
            current=CurrentWeather.from_dict(data["current"]) if "current" in data else None,
            hourly=HourlyWeather.from_dict(data["hourly"]) if "hourly" in data else None,
        )

    def __str__(self) -> str:
        if self.current:
            return f"Weather({self.current})"
        return "Weather(no current data)"


# ── Event payloads ────────────────────────────────────────────────────────────

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


EventPayload = Union[VesselPosition, WeatherAlert, SeaPhenomenon, Incident]

_PAYLOAD_PARSERS = {
    "VesselPosition": VesselPosition.from_dict,
    "WeatherAlert": WeatherAlert.from_dict,
    "SeaPhenomenon": SeaPhenomenon.from_dict,
    "Incident": Incident.from_dict,
}


def parse_payload(data: dict) -> EventPayload:
    """Parse event payload by discriminator field `type`."""
    payload_type = data.get("type")
    parser = _PAYLOAD_PARSERS.get(payload_type)
    if parser is None:
        raise ValueError(f"Unknown payload type: {payload_type!r}")
    return parser(data)


# ── Main event envelope ───────────────────────────────────────────────────────

@dataclass
class Event:
    """
    Main event envelope received from the server.

    The `weather` field is populated when the client subscribed with a
    weather LOD (`Lod.WEATHER_CURRENT` or `Lod.WEATHER_HOURLY`).
    """
    event_id: str
    h3_index: int
    timestamp: int  # Unix timestamp in milliseconds
    source: str
    confidence: float
    payload: EventPayload
    weather: Optional[WeatherEnrichment] = None

    @classmethod
    def from_dict(cls, data: dict) -> "Event":
        return cls(
            event_id=data["event_id"],
            h3_index=data["h3_index"],
            timestamp=data["timestamp"],
            source=data["source"],
            confidence=data["confidence"],
            payload=parse_payload(data["payload"]),
            weather=WeatherEnrichment.from_dict(data["weather"]) if "weather" in data else None,
        )

    @property
    def datetime(self) -> datetime:
        return datetime.fromtimestamp(self.timestamp / 1000)

    def __str__(self) -> str:
        base = f"Event({self.datetime.strftime('%H:%M:%S')} {self.payload})"
        if self.weather:
            return f"{base} | {self.weather}"
        return base


# ── REST API models ───────────────────────────────────────────────────────────

class HealthStatus(str, Enum):
    OK = "Ok"
    DEGRADED = "Degraded"
    DOWN = "Down"


@dataclass
class HealthResponse:
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
class SnapshotRequest:
    h3_cells: list[int]
    categories: Optional[list[str]] = None

    def to_dict(self) -> dict:
        result: dict = {"h3_cells": self.h3_cells}
        if self.categories:
            result["categories"] = self.categories
        return result


@dataclass
class SnapshotResponse:
    events: list[Event]

    @classmethod
    def from_dict(cls, data: dict) -> "SnapshotResponse":
        return cls(events=[Event.from_dict(e) for e in data.get("events", [])])
