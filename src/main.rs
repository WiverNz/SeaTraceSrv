use anyhow::Result;
use connectors::{AisStreamConfig, AisStreamConnector};
use control_api::{create_router, AppState};
use delivery::{Broadcaster, InMemoryBroadcaster};
use std::sync::Arc;
use tokio::net::TcpListener;
use tracing::info;
use tracing_subscriber::EnvFilter;

#[tokio::main]
async fn main() -> Result<()> {
    // ── Tracing ──────────────────────────────────────────────────────────────
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info")),
        )
        .init();

    // ── Shared broadcaster ───────────────────────────────────────────────────
    let broadcaster: Arc<dyn Broadcaster> = Arc::new(InMemoryBroadcaster::default());

    // ── AISStream connector ──────────────────────────────────────────────────
    let api_key = std::env::var("AISSTREAM_API_KEY")
        .expect("AISSTREAM_API_KEY environment variable must be set");

    let ais_config = AisStreamConfig::world(api_key);
    let connector = AisStreamConnector::new(ais_config, broadcaster.clone());

    // ── Axum HTTP + WebSocket server ─────────────────────────────────────────
    let bind_addr = std::env::var("BIND_ADDR").unwrap_or_else(|_| "0.0.0.0:8080".to_string());
    let listener = TcpListener::bind(&bind_addr).await?;
    info!("Listening on {}", bind_addr);

    let state = AppState::new(broadcaster.clone());
    let router = create_router(state);

    // ── Run both concurrently; either can exit (shouldn't in normal operation)
    tokio::select! {
        _ = connector.run() => {
            info!("AISStream connector stopped");
        }
        result = axum::serve(listener, router) => {
            result?;
        }
    }

    Ok(())
}
