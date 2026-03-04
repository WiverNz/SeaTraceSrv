#!/usr/bin/env python3
"""
SeaTraceSrv Client CLI

Connects to SeaTraceSrv and displays real-time vessel position events.

Usage:
    python client.py --host asgard.fritz.box --port 8080
    python client.py --host localhost --port 8080 --cells 608431123508232191
    python client.py health --host asgard.fritz.box
"""

import argparse
import asyncio
import sys
from datetime import datetime

# Add the scripts directory to path for local import
sys.path.insert(0, str(__file__).rsplit("/", 1)[0])

try:
    from seatrace_client import (
        RealtimeClient,
        SeaTraceClient,
        Event,
        VesselPosition,
    )
except ImportError as e:
    print(f"Error importing seatrace_client: {e}")
    print("Make sure websockets and httpx are installed:")
    print("  pip install websockets httpx")
    sys.exit(1)


def format_event(event: Event, verbose: bool = False) -> str:
    """Format an event for display."""
    time_str = event.datetime.strftime("%H:%M:%S")

    if isinstance(event.payload, VesselPosition):
        pos = event.payload
        line = f"[{time_str}] MMSI {pos.mmsi:>9} | "
        line += f"({pos.lat:>9.5f}, {pos.lon:>10.5f})"
        if pos.sog is not None:
            line += f" | {pos.sog:>5.1f} kn"
        if pos.cog is not None:
            line += f" | {pos.cog:>5.1f}°"
        return line
    else:
        return f"[{time_str}] {event.payload}"


async def stream_command(args):
    """Stream real-time events."""
    print(f"Connecting to ws://{args.host}:{args.port}/realtime...")

    try:
        async with RealtimeClient(args.host, args.port) as client:
            cells = args.cells if args.cells else []
            await client.subscribe(cells)

            mode = f"{len(cells)} cell(s)" if cells else "ALL (wildcard)"
            print(f"Subscribed to {mode}")
            print("-" * 70)

            if not args.verbose:
                print(f"{'Time':>10} | {'MMSI':>9} | {'Position':>23} | {'Speed':>7} | {'Course':>6}")
                print("-" * 70)

            count = 0
            async for event in client:
                count += 1
                if args.verbose:
                    print(f"\n--- Event #{count} ---")
                    print(f"  ID: {event.event_id}")
                    print(f"  H3: {event.h3_index}")
                    print(f"  Source: {event.source}")
                    print(f"  Time: {event.datetime}")
                    print(f"  Payload: {event.payload}")
                else:
                    print(format_event(event))

                if args.max_events and count >= args.max_events:
                    print(f"\nReceived {count} events, stopping.")
                    break

    except ConnectionRefusedError:
        print(f"Error: Connection refused. Is the server running at {args.host}:{args.port}?")
        sys.exit(1)
    except KeyboardInterrupt:
        print(f"\n\nDisconnected. Received {count} events.")


def health_command(args):
    """Check service health."""
    try:
        with SeaTraceClient(args.host, args.port) as client:
            health = client.get_health()
            print(f"Status: {health.status}")
            print("Components:")
            for name, status in health.components.items():
                print(f"  {name}: {status}")
    except Exception as e:
        print(f"Error: {e}")
        sys.exit(1)


def sources_command(args):
    """List data sources."""
    try:
        with SeaTraceClient(args.host, args.port) as client:
            sources = client.get_sources()
            if not sources:
                print("No sources configured.")
                return
            for src in sources:
                status = "active" if src.active else "inactive"
                print(f"  {src.id}: {src.health.value} ({status}, quality={src.quality_score:.2f})")
    except Exception as e:
        print(f"Error: {e}")
        sys.exit(1)


def main():
    parser = argparse.ArgumentParser(
        description="SeaTraceSrv Client - Real-time vessel tracking",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )

    # Global arguments
    parser.add_argument(
        "--host", "-H",
        default="asgard.fritz.box",
        help="Server hostname (default: asgard.fritz.box)"
    )
    parser.add_argument(
        "--port", "-p",
        type=int,
        default=8080,
        help="Server port (default: 8080)"
    )

    subparsers = parser.add_subparsers(dest="command", help="Command")

    # Stream command (default)
    stream_parser = subparsers.add_parser("stream", help="Stream real-time events")
    stream_parser.add_argument(
        "--cells", "-c",
        type=int,
        nargs="*",
        default=[],
        help="H3 cell indices to subscribe to (empty = all events)"
    )
    stream_parser.add_argument(
        "--verbose", "-v",
        action="store_true",
        help="Show full event details"
    )
    stream_parser.add_argument(
        "--max-events", "-n",
        type=int,
        default=None,
        help="Stop after receiving N events"
    )

    # Health command
    subparsers.add_parser("health", help="Check service health")

    # Sources command
    subparsers.add_parser("sources", help="List data sources")

    args = parser.parse_args()

    # Default to stream if no command specified
    if args.command is None:
        args.command = "stream"
        args.cells = []
        args.verbose = False
        args.max_events = None

    print("SeaTraceSrv Client")
    print("=" * 70)
    print(f"Server: {args.host}:{args.port}")
    print("=" * 70)

    if args.command == "stream":
        print("Press Ctrl+C to exit\n")
        asyncio.run(stream_command(args))
    elif args.command == "health":
        health_command(args)
    elif args.command == "sources":
        sources_command(args)


if __name__ == "__main__":
    main()
