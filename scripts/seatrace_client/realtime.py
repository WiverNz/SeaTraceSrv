"""
SeaTraceSrv WebSocket Realtime Client

Provides real-time streaming of vessel position events via WebSocket.
"""

import json
from typing import AsyncIterator, Callable, Optional, Awaitable

import websockets
from websockets.client import WebSocketClientProtocol

from .models import Event, Lod


class RealtimeClient:
    """
    WebSocket client for real-time event streaming.

    Usage (async iterator):
        async with RealtimeClient("localhost", 8080) as client:
            await client.subscribe(lod=[Lod.WEATHER_CURRENT])
            async for event in client:
                print(event)
                if event.weather:
                    print("  →", event.weather.current)

    Usage (callback):
        async def on_event(event: Event):
            print(event)

        client = RealtimeClient("localhost", 8080)
        await client.stream(on_event, lod=[Lod.WEATHER_CURRENT])
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
        h3_cells: Optional[list[int]] = None,
        lod: Optional[list[Lod]] = None,
    ):
        """
        Subscribe to events.

        Args:
            h3_cells: H3 cell indices to subscribe to.
                      Empty list or None = subscribe to ALL events (wildcard).
            lod:      Detail levels to request. Controls which enrichment data
                      is attached to each event.

                      Lod.WEATHER_CURRENT — adds current temperature, wind,
                          humidity at the vessel position (Open-Meteo).
                      Lod.WEATHER_HOURLY  — adds 24-hour hourly forecast
                          (implies WEATHER_CURRENT).

                      Future: Lod.WATER_CONDITIONS, Lod.DEPTH, Lod.WATER_CURRENTS
        """
        if not self._ws:
            raise RuntimeError("Not connected. Call connect() first.")

        msg: dict = {"h3_cells": h3_cells or []}
        if lod:
            msg["lod"] = [l.value for l in lod]

        await self._ws.send(json.dumps(msg))
        self._subscribed = True

    async def __aiter__(self) -> AsyncIterator[Event]:
        """Iterate over incoming events."""
        if not self._ws:
            raise RuntimeError("Not connected. Call connect() first.")
        if not self._subscribed:
            raise RuntimeError("Not subscribed. Call subscribe() first.")

        async for message in self._ws:
            try:
                data = json.loads(message)
                yield Event.from_dict(data)
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
                return Event.from_dict(data)
            except (json.JSONDecodeError, KeyError, ValueError):
                continue

    async def stream(
        self,
        callback: Callable[[Event], Awaitable[None]],
        h3_cells: Optional[list[int]] = None,
        lod: Optional[list[Lod]] = None,
        max_events: Optional[int] = None,
    ):
        """
        Stream events with a callback function.

        Args:
            callback:   Async function called for each event.
            h3_cells:   H3 cells to subscribe to (None/empty = all).
            lod:        Detail levels to request (see subscribe()).
            max_events: Stop after receiving this many events (None = unlimited).
        """
        if not self._ws:
            await self.connect()

        await self.subscribe(h3_cells, lod)

        count = 0
        async for event in self:
            await callback(event)
            count += 1
            if max_events and count >= max_events:
                break


async def stream_events(
    host: str = "localhost",
    port: int = 8080,
    h3_cells: Optional[list[int]] = None,
    lod: Optional[list[Lod]] = None,
    use_wss: bool = False,
) -> AsyncIterator[Event]:
    """
    Convenience async generator for streaming events.

    Usage:
        async for event in stream_events("localhost", 8080, lod=[Lod.WEATHER_CURRENT]):
            print(event)
    """
    async with RealtimeClient(host, port, use_wss) as client:
        await client.subscribe(h3_cells, lod)
        async for event in client:
            yield event
