#!/usr/bin/env python3
"""
SeaTraceSrv Client CLI

Connects to SeaTraceSrv and displays real-time vessel position events.

Usage:
    python client.py stream --section 56.0 54.0 13.0 10.0
    python client.py stream --section 56.0 54.0 13.0 10.0 --lod weather_current
    python client.py health --host localhost
    python client.py sources

Section format: --section NORTH SOUTH EAST WEST  (decimal degrees)
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
        Section,
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

    n, s, e, w = args.section
    section = Section(north=n, south=s, east=e, west=w)

    print(f"Connecting to ws://{args.host}:{args.port}/realtime...")
    lod_str = ", ".join(l.value for l in lod) if lod else "vessels only"
    print(f"Section: {section}  |  LOD: {lod_str}")

    count = 0
    try:
        async with RealtimeClient(args.host, args.port) as client:
            await client.subscribe(section, lod=lod or None)
            print("-" * 80)

            if not args.verbose:
                header = f"{'Time':>10} | {'MMSI':>9} | {'Position':>23} | {'Speed':>7} | {'Course':>6}"
                if lod:
                    header += f" | {'Weather (temp / wind / rh)':>30}"
                print(header)
                print("-" * 80)

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
    # Shared parent parser so --host/--port can appear before OR after the subcommand
    connection_parser = argparse.ArgumentParser(add_help=False)
    connection_parser.add_argument("--host", "-H", default="asgard.fritz.box",
                                   help="Server hostname (default: asgard.fritz.box)")
    connection_parser.add_argument("--port", "-p", type=int, default=8080,
                                   help="Server port (default: 8080)")

    parser = argparse.ArgumentParser(
        description="SeaTraceSrv Client — Real-time vessel tracking",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        parents=[connection_parser],
        epilog="""
examples:
  python client.py stream --section 56.0 54.0 13.0 10.0
  python client.py stream --section 56.0 54.0 13.0 10.0 --lod weather_current
  python client.py stream --section 56.0 54.0 13.0 10.0 --lod weather_current weather_hourly --verbose
  python client.py health
  python client.py sources

section format: NORTH SOUTH EAST WEST (decimal degrees)
  example Baltic Sea: --section 56.0 54.0 13.0 10.0
        """,
    )

    subparsers = parser.add_subparsers(dest="command")

    # stream
    stream_parser = subparsers.add_parser("stream", help="Stream real-time events",
                                         parents=[connection_parser])
    stream_parser.add_argument(
        "--section", "-s", type=float, nargs=4, required=True,
        metavar=("NORTH", "SOUTH", "EAST", "WEST"),
        help="Geographic bounding box in decimal degrees: NORTH SOUTH EAST WEST",
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
    subparsers.add_parser("health", help="Check service health",
                          parents=[connection_parser])

    # sources
    subparsers.add_parser("sources", help="List data sources",
                          parents=[connection_parser])

    args = parser.parse_args()

    if args.command is None:
        parser.print_help()
        sys.exit(0)

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
