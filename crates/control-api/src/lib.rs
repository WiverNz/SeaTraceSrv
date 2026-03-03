pub mod routes;
pub mod state;

pub use state::AppState;

use axum::{
    routing::{get, post},
    Router,
};
use routes::{control, realtime};

pub fn create_router(state: AppState) -> Router {
    Router::new()
        .route("/health", get(control::get_health))
        .route("/sources", get(control::get_sources))
        .route("/snapshot", post(control::pull_snapshot))
        .route("/realtime", get(realtime::realtime_handler))
        .with_state(state)
}
