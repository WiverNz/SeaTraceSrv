"""
SeaTraceSrv WebSocket Realtime Client

Provides real-time streaming of vessel position events via WebSocket.
"""

import asyncio
import json
from typing import AsyncIterator, Callable, Optional, Awaitable

import websockets
from websockets.client import WebSocketClientProtocol

from .models import Event


class RealtimeClient:
    """
    WebSocket client for real-time event streaming.

    Usage:
        # Async iterator pattern
        async with RealtimeClient("asgard.fritz.box", 8080) as client:
            await client.subscribe([])  # Empty list = all events
            async for event in client:
                print(event)

        # Callback pattern
        async def on_event(event: Event):
            print(f"Received: {event}")

        client = RealtimeClient("asgard.fritz.box", 8080)
        await client.stream(on_event)
    """

    def __init__(
        self,
        host: str = "localhost",
        port: int = 8080,
        use_wss: bool = False,
    ):
        """
        Initialize the realtime client.

        Args:
            host: Server hostname
            port: Server port
            use_wss: Use WSS (secure WebSocket) instead of WS
        """
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

    async def subscribe(self, h3_cells: Optional[list[int]] = None):
        """
        Subscribe to events for specific H3 cells.

        Args:
            h3_cells: List of H3 cell indices to subscribe to.
                      Empty list or None = subscribe to ALL events (wildcard).
        """
        if not self._ws:
            raise RuntimeError("Not connected. Call connect() first.")

        cells = h3_cells if h3_cells is not None else []
        subscription = {"h3_cells": cells}
        await self._ws.send(json.dumps(subscription))
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
            except (json.JSONDecodeError, KeyError, ValueError) as e:
                # Skip malformed messages
                continue

    async def recv(self) -> Event:
        """
        Receive a single event.

        Returns:
            The next Event from the stream

        Raises:
            RuntimeError: If not connected or subscribed
        """
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
                # Skip malformed messages, try next
                continue

    async def stream(
        self,
        callback: Callable[[Event], Awaitable[None]],
        h3_cells: Optional[list[int]] = None,
        max_events: Optional[int] = None,
    ):
        """
        Stream events with a callback function.

        Args:
            callback: Async function called for each event
            h3_cells: H3 cells to subscribe to (None/empty = all)
            max_events: Maximum events to receive (None = unlimited)
        """
        if not self._ws:
            await self.connect()

        await self.subscribe(h3_cells)

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
    use_wss: bool = False,
) -> AsyncIterator[Event]:
    """
    Convenience function for streaming events.

    Usage:
        async for event in stream_events("asgard.fritz.box", 8080):
            print(event)
    """
    async with RealtimeClient(host, port, use_wss) as client:
        await client.subscribe(h3_cells)
        async for event in client:
            yield event
