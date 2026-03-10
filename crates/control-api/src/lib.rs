pub mod enrichment;
pub mod lod;
pub mod routes;
pub mod state;
pub mod vessel_catalog;
pub mod weather;

pub use state::AppState;

use axum::{
    routing::{get, post},
    Router,
};
use delivery::Broadcaster;
use routes::{control, realtime};
use std::sync::Arc;
use tokio::sync::RwLock;

pub fn create_router(state: AppState) -> Router {
    Router::new()
        .route("/health", get(control::get_health))
        .route("/sources", get(control::get_sources))
        .route("/snapshot", post(control::pull_snapshot))
        .route("/realtime", get(realtime::realtime_handler))
        .with_state(state)
}

/// Creates an `AppState` with a Redis connection pool built from `redis_url`.
///
/// Also returns an `Arc<RwLock<Option<String>>>` for the active catalog version
/// so callers can spawn `vessel_catalog::start_catalog_poller` separately if needed.
pub async fn create_app_state(
    broadcaster: Arc<dyn Broadcaster>,
    redis_url: &str,
) -> anyhow::Result<AppState> {
    let manager = bb8_redis::RedisConnectionManager::new(redis_url)?;
    let redis_pool = bb8::Pool::builder().build(manager).await?;
    let active_catalog_version = Arc::new(RwLock::new(None::<String>));
    Ok(AppState::new(broadcaster, redis_pool, active_catalog_version))
}
