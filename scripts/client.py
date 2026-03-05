#!/usr/bin/env python3
"""
SeaTraceSrv Client CLI

Connects to SeaTraceSrv and displays real-time vessel position events.

Usage:
    python client.py --host localhost --port 8080
    python client.py stream --lod weather_current
    python client.py stream --lod weather_current weather_hourly --cells 608431123508232191
    python client.py health --host localhost
    python client.py sources
"""

import argparse
import asyncio
import sys
from datetime import datetime

sys.path.insert(0, str(__file__).rsplit("/", 1)[0])

try:
    from seatrace_client import (
        RealtimeClient,
        SeaTraceClient,
        Event,
        VesselPosition,
        Lod,
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
    else:
        line = f"[{time_str}] {event.payload}"

    if event.weather and event.weather.current:
        w = event.weather.current
        line += f" | {w.temperature_2m:.1f}°C  {w.wind_speed_10m:.1f}km/h  {w.relative_humidity_2m:.0f}%rh"

    return line


def format_event_verbose(event: Event, index: int) -> str:
    """Format a full event with all fields for verbose output."""
    lines = [
        f"--- Event #{index} ---",
        f"  ID:         {event.event_id}",
        f"  H3:         {event.h3_index}",
        f"  Source:     {event.source}",
        f"  Time:       {event.datetime}",
        f"  Confidence: {event.confidence:.2f}",
        f"  Payload:    {event.payload}",
    ]
    if event.weather:
        w = event.weather
        if w.current:
            lines.append(
                f"  Weather:    {w.current.temperature_2m:.1f}°C  "
                f"wind {w.current.wind_speed_10m:.1f} km/h  "
                f"rh {w.current.relative_humidity_2m:.0f}%  "
                f"@ {w.current.time}"
            )
        if w.hourly:
            lines.append(f"  Forecast ({len(w.hourly.time)}h):")
            for i in range(min(3, len(w.hourly.time))):
                lines.append(f"    {w.hourly.at_hour(i)}")
            if len(w.hourly.time) > 3:
                lines.append(f"    … ({len(w.hourly.time) - 3} more hours)")
    return "\n".join(lines)


async def stream_command(args):
    """Stream real-time events."""
    lod = [Lod(v) for v in args.lod] if args.lod else []

    print(f"Connecting to ws://{args.host}:{args.port}/realtime...")

    try:
        async with RealtimeClient(args.host, args.port) as client:
            cells = args.cells if args.cells else []
            await client.subscribe(cells, lod=lod or None)

            mode = f"{len(cells)} cell(s)" if cells else "ALL (wildcard)"
            lod_str = ", ".join(l.value for l in lod) if lod else "vessels only"
            print(f"Subscribed to {mode}  |  LOD: {lod_str}")
            print("-" * 80)

            if not args.verbose:
                header = f"{'Time':>10} | {'MMSI':>9} | {'Position':>23} | {'Speed':>7} | {'Course':>6}"
                if lod:
                    header += f" | {'Weather (temp / wind / rh)':>30}"
                print(header)
                print("-" * 80)

            count = 0
            async for event in client:
                count += 1
                if args.verbose:
                    print(format_event_verbose(event, count))
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
    with SeaTraceClient(args.host, args.port) as client:
        health = client.get_health()
        print(f"Status: {health.status}")
        print("Components:")
        for name, status in health.components.items():
            print(f"  {name}: {status}")


def sources_command(args):
    with SeaTraceClient(args.host, args.port) as client:
        sources = client.get_sources()
        if not sources:
            print("No sources configured.")
            return
        for src in sources:
            status = "active" if src.active else "inactive"
            print(f"  {src.id}: {src.health.value} ({status}, quality={src.quality_score:.2f})")


def main():
    parser = argparse.ArgumentParser(
        description="SeaTraceSrv Client — Real-time vessel tracking",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
examples:
  python client.py stream
  python client.py stream --lod weather_current
  python client.py stream --lod weather_current weather_hourly --verbose
  python client.py stream --cells 608431123508232191 --lod weather_current
  python client.py health
  python client.py sources
        """,
    )

    parser.add_argument("--host", "-H", default="asgard.fritz.box",
                        help="Server hostname (default: asgard.fritz.box)")
    parser.add_argument("--port", "-p", type=int, default=8080,
                        help="Server port (default: 8080)")

    subparsers = parser.add_subparsers(dest="command")

    # stream
    stream_parser = subparsers.add_parser("stream", help="Stream real-time events")
    stream_parser.add_argument(
        "--cells", "-c", type=int, nargs="*", default=[],
        help="H3 cell indices to subscribe to (empty = all events)",
    )
    stream_parser.add_argument(
        "--lod", "-l",
        nargs="*",
        metavar="LOD",
        choices=[l.value for l in Lod],
        default=[],
        help=(
            "Detail levels to request. Available: "
            + ", ".join(l.value for l in Lod)
        ),
    )
    stream_parser.add_argument("--verbose", "-v", action="store_true",
                               help="Show full event details")
    stream_parser.add_argument("--max-events", "-n", type=int, default=None,
                               help="Stop after receiving N events")

    # health
    subparsers.add_parser("health", help="Check service health")

    # sources
    subparsers.add_parser("sources", help="List data sources")

    args = parser.parse_args()

    if args.command is None:
        args.command = "stream"
        args.cells = []
        args.lod = []
        args.verbose = False
        args.max_events = None

    print("SeaTraceSrv Client")
    print("=" * 80)
    print(f"Server: {args.host}:{args.port}")
    print("=" * 80)

    if args.command == "stream":
        print("Press Ctrl+C to exit\n")
        asyncio.run(stream_command(args))
    elif args.command == "health":
        health_command(args)
    elif args.command == "sources":
        sources_command(args)


if __name__ == "__main__":
    main()
