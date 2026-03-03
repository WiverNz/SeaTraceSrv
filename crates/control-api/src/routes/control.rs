use axum::{
    extract::State,
    http::StatusCode,
    response::IntoResponse,
    Json,
};
use core_model::api::types::{HealthResponse, SnapshotRequest, SnapshotResponse, SourceStatus};
use std::collections::HashMap;

use crate::state::AppState;

pub async fn get_health(State(_state): State<AppState>) -> impl IntoResponse {
    let mut components = HashMap::new();
    components.insert("broadcaster".to_string(), "ok".to_string());

    let response = HealthResponse {
        status: "ok".to_string(),
        components,
    };

    (StatusCode::OK, Json(response))
}

pub async fn get_sources(State(_state): State<AppState>) -> impl IntoResponse {
    // В MVP пока возвращаем пустой список или заглушку
    let sources: Vec<SourceStatus> = vec![];
    
    (StatusCode::OK, Json(sources))
}

pub async fn pull_snapshot(
    State(_state): State<AppState>,
    Json(_req): Json<SnapshotRequest>,
) -> impl IntoResponse {
    // В MVP пока нет хранилища снапшотов
    let response = SnapshotResponse {
        events: vec![],
    };

    (StatusCode::OK, Json(response))
}
