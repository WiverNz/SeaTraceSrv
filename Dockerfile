# ─── Stage 1: builder ────────────────────────────────────────────────────────
FROM rust:1.86-slim AS builder

# Install system dependencies needed by native-tls (OpenSSL)
RUN apt-get update && apt-get install -y \
    pkg-config \
    libssl-dev \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /build

# Copy manifests first for better layer caching —
# dependencies are re-downloaded only when Cargo.toml / Cargo.lock changes.
COPY Cargo.toml Cargo.lock ./
COPY crates/core-model/Cargo.toml     crates/core-model/Cargo.toml
COPY crates/connectors/Cargo.toml     crates/connectors/Cargo.toml
COPY crates/aggregator/Cargo.toml     crates/aggregator/Cargo.toml
COPY crates/data-store/Cargo.toml     crates/data-store/Cargo.toml
COPY crates/delivery/Cargo.toml       crates/delivery/Cargo.toml
COPY crates/control-api/Cargo.toml    crates/control-api/Cargo.toml
COPY crates/integration-tests/Cargo.toml crates/integration-tests/Cargo.toml
COPY workers/catalog-worker/Cargo.toml   workers/catalog-worker/Cargo.toml

# Stub out every lib/main so Cargo can resolve + cache all deps without sources.
RUN mkdir -p src && echo 'fn main(){}' > src/main.rs && \
    for crate in core-model connectors aggregator data-store delivery control-api integration-tests; do \
    mkdir -p crates/$crate/src && \
    echo 'pub fn _stub(){}' > crates/$crate/src/lib.rs; \
    done && \
    mkdir -p workers/catalog-worker/src && echo 'fn main(){}' > workers/catalog-worker/src/main.rs && \
    # core-model has a build script that reads openapi.yaml — provide a minimal copy
    mkdir -p api-contracts

# Copy the real OpenAPI spec (needed by core-model's build.rs)
COPY api-contracts/ api-contracts/

# Build deps only (sources are stubs → incremental layer)
RUN cargo build --release --bin seatracesrv 2>/dev/null || true

# Now copy all real source files
COPY src/          src/
COPY crates/       crates/
COPY workers/      workers/

# Touch to bust Cargo's incremental cache on stub files
RUN touch src/main.rs crates/*/src/lib.rs workers/catalog-worker/src/main.rs

# Final release build
RUN cargo build --release --bin seatracesrv

# ─── Stage 2: minimal runtime image ──────────────────────────────────────────
FROM debian:bookworm-slim AS runtime

# ca-certificates → TLS root CAs for wss:// connection to AISStream
RUN apt-get update && apt-get install -y \
    ca-certificates \
    libssl3 \
    && rm -rf /var/lib/apt/lists/*

# Non-root user for Kubernetes best-practice
RUN useradd -ms /bin/bash seatrace
USER seatrace

WORKDIR /app
COPY --from=builder /build/target/release/seatracesrv ./seatracesrv

# ── Runtime config ────────────────────────────────────────────────────────────
# Pass secrets via Kubernetes Secret → envFrom, not baked into the image.
ENV BIND_ADDR="0.0.0.0:8080"
ENV RUST_LOG="info"
# AISSTREAM_API_KEY must be injected at runtime

EXPOSE 8080

ENTRYPOINT ["./seatracesrv"]
