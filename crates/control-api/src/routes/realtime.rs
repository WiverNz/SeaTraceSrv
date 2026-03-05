use axum::{
    extract::{
        ws::{Message, WebSocket, WebSocketUpgrade},
        State,
    },
    response::IntoResponse,
};
use core_model::api::types::EventPayload;
use core_model::Event;
use futures_util::stream::StreamExt;
use serde::{Deserialize, Serialize};
use tracing::warn;

use crate::{lod::Lod, state::AppState, weather::WeatherEnrichment};

/// Message sent by the client to establish a subscription.
///
/// ```json
/// { "h3_cells": ["8a2a1072b59ffff"], "lod": ["weather_current"] }
/// ```
///
/// `h3_cells` — H3 cell indices to subscribe to; empty list = all events.
/// `lod`      — optional detail levels (default: vessels only).
#[derive(Debug, Deserialize)]
pub struct SubscribeMessage {
    pub h3_cells: Vec<u64>,
    #[serde(default)]
    pub lod: Vec<Lod>,
}

/// An event optionally enriched with weather data, sent to the client.
#[derive(Serialize)]
struct EnrichedEvent {
    #[serde(flatten)]
    inner: Event,
    #[serde(skip_serializing_if = "Option::is_none")]
    weather: Option<WeatherEnrichment>,
}

/// Extract the lat/lon from a vessel/incident/phenomenon event payload.
/// Returns `None` for event types without a point position (e.g. WeatherAlert).
fn extract_position(event: &Event) -> Option<(f64, f64)> {
    match &event.payload {
        EventPayload::VesselPosition(p) => Some((p.lat, p.lon)),
        EventPayload::SeaPhenomenon(p) => Some((p.lat, p.lon)),
        EventPayload::Incident(p) => Some((p.lat, p.lon)),
        EventPayload::WeatherAlert(_) => None,
    }
}

pub async fn realtime_handler(
    ws: WebSocketUpgrade,
    State(state): State<AppState>,
) -> impl IntoResponse {
    ws.on_upgrade(|socket| handle_socket(socket, state))
}

async fn handle_socket(mut socket: WebSocket, state: AppState) {
    // 1. Wait for the first message: the subscription / LOD request.
    let first_msg = match socket.next().await {
        Some(Ok(msg)) => msg,
        _ => return,
    };

    let text = match first_msg {
        Message::Text(t) => t,
        _ => {
            let _ = socket
                .send(Message::Text("Expected text message".into()))
                .await;
            return;
        }
    };

    let sub: SubscribeMessage = match serde_json::from_str(&text) {
        Ok(s) => s,
        Err(e) => {
            let _ = socket
                .send(Message::Text(
                    format!("Invalid subscription JSON: {}", e).into(),
                ))
                .await;
            return;
        }
    };

    // Derive weather flags from the requested LODs once, before the loop.
    let need_weather_current =
        sub.lod.contains(&Lod::WeatherCurrent) || sub.lod.contains(&Lod::WeatherHourly);
    let need_weather_hourly = sub.lod.contains(&Lod::WeatherHourly);

    let client_id = uuid::Uuid::new_v4().to_string();
    let mut rx = state.broadcaster.subscribe(&client_id, sub.h3_cells).await;

    // 2. Forward events to the client, enriching them as requested.
    loop {
        tokio::select! {
            result = rx.recv() => {
                match result {
                    Ok(event) => {
                        // Fetch weather only when the LOD requests it and the
                        // event has a point position to query.
                        let weather = if need_weather_current {
                            if let Some((lat, lon)) = extract_position(&event) {
                                match state
                                    .weather_client
                                    .get(
                                        event.h3_index,
                                        lat,
                                        lon,
                                        need_weather_current,
                                        need_weather_hourly,
                                    )
                                    .await
                                {
                                    Ok(w) => Some(w),
                                    Err(e) => {
                                        warn!("Weather fetch failed for h3={}: {:#}", event.h3_index, e);
                                        None
                                    }
                                }
                            } else {
                                None
                            }
                        } else {
                            None
                        };

                        let enriched = EnrichedEvent { inner: event, weather };
                        if let Ok(json) = serde_json::to_string(&enriched) {
                            if socket.send(Message::Text(json.into())).await.is_err() {
                                break;
                            }
                        }
                    }
                    Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => {
                        // Client is too slow to consume; skip lagged events.
                        continue;
                    }
                    Err(tokio::sync::broadcast::error::RecvError::Closed) => break,
                }
            }
            client_msg = socket.next() => {
                match client_msg {
                    Some(Ok(Message::Close(_))) => break,
                    Some(Ok(_)) => {},
                    _ => break,
                }
            }
        }
    }
}
