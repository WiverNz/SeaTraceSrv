"""
SeaTraceSrv WebSocket Realtime Client

Provides real-time streaming of vessel position events via WebSocket.
"""

import json
import sys
from typing import AsyncIterator, Callable, Optional, Awaitable

import websockets
from websockets.client import WebSocketClientProtocol

from .models import Event, Lod, Section, VesselPosition


class RealtimeClient:
    """
    WebSocket client for real-time event streaming.

    Usage (async iterator):
        async with RealtimeClient("localhost", 8080) as client:
            section = Section(north=56.0, south=54.0, east=13.0, west=10.0)
            await client.subscribe(section, lod=[Lod.WEATHER_CURRENT])
            async for event in client:
                print(event)

    Usage (callback):
        async def on_event(event: Event):
            print(event)

        client = RealtimeClient("localhost", 8080)
        await client.stream(on_event, section=section, lod=[Lod.WEATHER_CURRENT])
    """

    def __init__(
        self,
        host: str = "localhost",
        port: int = 8080,
        use_wss: bool = False,
    ):
        self.host = host
        self.port = port
        scheme = "wss" if use_wss else "ws"
        self.url = f"{scheme}://{host}:{port}/realtime"
        self._ws: Optional[WebSocketClientProtocol] = None
        self._subscribed = False
        self._section: Optional[Section] = None

    async def __aenter__(self):
        await self.connect()
        return self

    async def __aexit__(self, exc_type, exc_val, exc_tb):
        await self.close()

    async def connect(self):
        """Establish WebSocket connection."""
        self._ws = await websockets.connect(self.url)
        self._subscribed = False

    async def close(self):
        """Close WebSocket connection."""
        if self._ws:
            await self._ws.close()
            self._ws = None
            self._subscribed = False

    async def subscribe(
        self,
        section: Optional[Section] = None,
        lod: Optional[list[Lod]] = None,
    ):
        """
        Subscribe to events within a geographic section (viewport).
        Can be called multiple times to change the section.

        Args:
            section:  Bounding box to subscribe to. If None, no subscription
                      is sent (server requires a viewport since commit 65e49f5).
            lod:      Detail levels to request.

                      Lod.WEATHER_CURRENT — adds current temperature, wind,
                          humidity at the vessel position (Open-Meteo).
                      Lod.WEATHER_HOURLY  — adds 24-hour hourly forecast
                          (implies WEATHER_CURRENT).
        """
        if not self._ws:
            raise RuntimeError("Not connected. Call connect() first.")
        if section is None:
            raise ValueError(
                "A Section (bounding box) is required. "
                "The server only delivers events for a specific geographic area."
            )

        self._section = section
        msg: dict = {"viewport": section.to_dict()}
        if lod:
            msg["lod"] = [l.value for l in lod]

        await self._ws.send(json.dumps(msg))
        self._subscribed = True

    def _check_bounds(self, event: Event) -> None:
        """
        Validate that an event's position falls inside the subscribed section.
        Prints an error to stderr if it does not — this indicates a server bug.
        """
        if self._section is None:
            return
        payload = event.payload
        if not isinstance(payload, VesselPosition):
            return
        if not self._section.contains(payload.lat, payload.lon):
            print(
                f"ERROR: received ship MMSI={payload.mmsi} at "
                f"({payload.lat:.5f}, {payload.lon:.5f}) which is OUTSIDE "
                f"the subscribed section [{self._section}]",
                file=sys.stderr,
            )

    async def __aiter__(self) -> AsyncIterator[Event]:
        """Iterate over incoming events, ignoring Ack and Error messages."""
        if not self._ws:
            raise RuntimeError("Not connected. Call connect() first.")
        if not self._subscribed:
            raise RuntimeError("Not subscribed. Call subscribe() first.")

        async for message in self._ws:
            try:
                data = json.loads(message)
                msg_type = data.get("type", "")
                if msg_type in ("SubscribeAck", "Error"):
                    if msg_type == "Error":
                        print(f"Server Error: {data.get('message')}", file=sys.stderr)
                    continue

                event = Event.from_dict(data)
                self._check_bounds(event)
                yield event
            except (json.JSONDecodeError, KeyError, ValueError):
                continue

    async def recv(self) -> Event:
        """Receive a single event."""
        if not self._ws:
            raise RuntimeError("Not connected. Call connect() first.")
        if not self._subscribed:
            raise RuntimeError("Not subscribed. Call subscribe() first.")

        while True:
            message = await self._ws.recv()
            try:
                data = json.loads(message)
                msg_type = data.get("type", "")
                if msg_type in ("SubscribeAck", "Error"):
                    if msg_type == "Error":
                        print(f"Server Error: {data.get('message')}", file=sys.stderr)
                    continue

                event = Event.from_dict(data)
                self._check_bounds(event)
                return event
            except (json.JSONDecodeError, KeyError, ValueError):
                continue

    async def stream(
        self,
        callback: Callable[[Event], Awaitable[None]],
        section: Optional[Section] = None,
        lod: Optional[list[Lod]] = None,
        max_events: Optional[int] = None,
    ):
        """
        Stream events with a callback function.

        Args:
            callback:   Async function called for each event.
            section:    Bounding box to subscribe to (required).
            lod:        Detail levels to request (see subscribe()).
            max_events: Stop after receiving this many events (None = unlimited).
        """
        if not self._ws:
            await self.connect()

        await self.subscribe(section, lod)

        count = 0
        async for event in self:
            await callback(event)
            count += 1
            if max_events and count >= max_events:
                break


async def stream_events(
    host: str = "localhost",
    port: int = 8080,
    section: Optional[Section] = None,
    lod: Optional[list[Lod]] = None,
    use_wss: bool = False,
) -> AsyncIterator[Event]:
    """
    Convenience async generator for streaming events within a geographic section.

    Usage:
        section = Section(north=56.0, south=54.0, east=13.0, west=10.0)
        async for event in stream_events("localhost", 8080, section=section):
            print(event)
    """
    async with RealtimeClient(host, port, use_wss) as client:
        await client.subscribe(section, lod)
        async for event in client:
            yield event
