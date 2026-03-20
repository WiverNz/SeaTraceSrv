# Build from the workspace root:
#   docker build -f workers/catalog-worker/Dockerfile -t catalog-worker:latest .

# ── Stage 1: builder ──────────────────────────────────────────────────────────
FROM rust:1.86-slim-bookworm AS builder

WORKDIR /workspace

RUN apt-get update \
    && apt-get install -y --no-install-recommends pkg-config libssl-dev \
    && rm -rf /var/lib/apt/lists/*

# Copy the full workspace so Cargo can resolve inter-crate dependencies.
COPY . .

# Build only the catalog-worker binary in release mode.
RUN cargo build --release --bin catalog-worker

# ── Stage 2: runtime ──────────────────────────────────────────────────────────
FROM debian:bookworm-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates \
    && rm -rf /var/lib/apt/lists/*

COPY --from=builder /workspace/target/release/catalog-worker /usr/local/bin/catalog-worker

# ── Configuration (override with -e or a ConfigMap) ──────────────────────────
ENV REDIS_URL=redis://redis:6379
# Rebuild catalog every hour (in seconds).
ENV CATALOG_REFRESH_INTERVAL_SECS=3600
# Keep the 3 most recent catalog versions in Redis.
ENV CATALOG_VERSIONS_TO_KEEP=3
# Volume mount point for downloaded map / chart data.
ENV DATA_DIR=/data
ENV RUST_LOG=info

VOLUME ["/data"]

CMD ["catalog-worker"]
