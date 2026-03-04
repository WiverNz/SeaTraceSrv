"""
SeaTraceSrv HTTP API Client

Provides access to the REST API endpoints:
- GET /health - Health check
- GET /sources - List data sources
- POST /snapshot - Pull state snapshot
"""

import httpx
from typing import Optional

from .models import HealthResponse, SourceStatus, SnapshotRequest, SnapshotResponse


class SeaTraceClient:
    """
    HTTP client for SeaTraceSrv Control API.

    Usage:
        client = SeaTraceClient("asgard.fritz.box", 8080)

        # Check health
        health = client.get_health()
        print(health.status)

        # List sources
        sources = client.get_sources()

        # Pull snapshot
        snapshot = client.pull_snapshot([608431123508232191])
    """

    def __init__(
        self,
        host: str = "localhost",
        port: int = 8080,
        timeout: float = 30.0,
        use_https: bool = False,
    ):
        """
        Initialize the client.

        Args:
            host: Server hostname
            port: Server port
            timeout: Request timeout in seconds
            use_https: Use HTTPS instead of HTTP
        """
        self.host = host
        self.port = port
        self.timeout = timeout
        scheme = "https" if use_https else "http"
        self.base_url = f"{scheme}://{host}:{port}"
        self._client = httpx.Client(timeout=timeout)

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        self.close()

    def close(self):
        """Close the HTTP client."""
        self._client.close()

    def get_health(self) -> HealthResponse:
        """
        Get service health status.

        Returns:
            HealthResponse with status and component health
        """
        response = self._client.get(f"{self.base_url}/health")
        response.raise_for_status()
        return HealthResponse.from_dict(response.json())

    def get_sources(self) -> list[SourceStatus]:
        """
        List all active and defined data sources.

        Returns:
            List of SourceStatus objects
        """
        response = self._client.get(f"{self.base_url}/sources")
        response.raise_for_status()
        return [SourceStatus.from_dict(s) for s in response.json()]

    def pull_snapshot(
        self,
        h3_cells: list[int],
        categories: Optional[list[str]] = None,
    ) -> SnapshotResponse:
        """
        Request state snapshot for specific H3 cells.

        Args:
            h3_cells: List of H3 cell indices
            categories: Optional list of event categories to filter

        Returns:
            SnapshotResponse containing events
        """
        request = SnapshotRequest(h3_cells=h3_cells, categories=categories)
        response = self._client.post(
            f"{self.base_url}/snapshot",
            json=request.to_dict(),
        )
        response.raise_for_status()
        return SnapshotResponse.from_dict(response.json())


class AsyncSeaTraceClient:
    """
    Async HTTP client for SeaTraceSrv Control API.

    Usage:
        async with AsyncSeaTraceClient("asgard.fritz.box", 8080) as client:
            health = await client.get_health()
            print(health.status)
    """

    def __init__(
        self,
        host: str = "localhost",
        port: int = 8080,
        timeout: float = 30.0,
        use_https: bool = False,
    ):
        self.host = host
        self.port = port
        self.timeout = timeout
        scheme = "https" if use_https else "http"
        self.base_url = f"{scheme}://{host}:{port}"
        self._client = httpx.AsyncClient(timeout=timeout)

    async def __aenter__(self):
        return self

    async def __aexit__(self, exc_type, exc_val, exc_tb):
        await self.close()

    async def close(self):
        """Close the HTTP client."""
        await self._client.aclose()

    async def get_health(self) -> HealthResponse:
        """Get service health status."""
        response = await self._client.get(f"{self.base_url}/health")
        response.raise_for_status()
        return HealthResponse.from_dict(response.json())

    async def get_sources(self) -> list[SourceStatus]:
        """List all active and defined data sources."""
        response = await self._client.get(f"{self.base_url}/sources")
        response.raise_for_status()
        return [SourceStatus.from_dict(s) for s in response.json()]

    async def pull_snapshot(
        self,
        h3_cells: list[int],
        categories: Optional[list[str]] = None,
    ) -> SnapshotResponse:
        """Request state snapshot for specific H3 cells."""
        request = SnapshotRequest(h3_cells=h3_cells, categories=categories)
        response = await self._client.post(
            f"{self.base_url}/snapshot",
            json=request.to_dict(),
        )
        response.raise_for_status()
        return SnapshotResponse.from_dict(response.json())
