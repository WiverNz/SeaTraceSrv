use axum::{
    extract::{
        ws::{Message, WebSocket, WebSocketUpgrade},
        State,
    },
    response::IntoResponse,
};
use delivery::Viewport;
use futures_util::stream::StreamExt;
use serde::{Deserialize, Serialize};

use crate::{lod::Lod, state::AppState};
use tracing::{info, warn};

/// Message sent by the client to establish or update a subscription.
#[derive(Debug, Deserialize)]
pub struct SubscribeMessage {
    pub viewport: Viewport,
    #[serde(default)]
    pub lod: Vec<Lod>,
}

#[derive(Serialize)]
struct SubscribeAck {
    #[serde(rename = "type")]
    type_: &'static str,
    status: &'static str,
}

#[derive(Serialize)]
struct ErrorMessage {
    #[serde(rename = "type")]
    type_: &'static str,
    message: String,
}

/// Compute the diagonal distance of a viewport in kilometres (Haversine).
fn viewport_diagonal_km(vp: &Viewport) -> f64 {
    let r = 6371.0; // Earth radius in km
    let d_lat = (vp.north - vp.south).to_radians();
    let d_lon = (vp.east - vp.west).to_radians();
    let lat1 = vp.south.to_radians();
    let lat2 = vp.north.to_radians();

    let a = (d_lat / 2.0).sin().powi(2) + lat1.cos() * lat2.cos() * (d_lon / 2.0).sin().powi(2);
    let c = 2.0 * a.sqrt().asin();
    r * c
}

pub async fn realtime_handler(
    ws: WebSocketUpgrade,
    State(state): State<AppState>,
) -> impl IntoResponse {
    ws.on_upgrade(|socket| handle_socket(socket, state))
}

async fn handle_socket(mut socket: WebSocket, state: AppState) {
    let client_id = uuid::Uuid::new_v4();
    let short_id = &client_id.to_string()[..8];
    info!(client_id = short_id, "WebSocket client connected");

    // Create an empty subscription out of the gate to get the receiver channel.
    // The client will receive nothing until they send a SubscribeMessage.
    let client_id_str = client_id.to_string();
    let mut rx = state.broadcaster.subscribe(&client_id_str, None).await;
    let mut active_lods: Vec<Lod> = vec![];

    loop {
        tokio::select! {
            // 1. Handle incoming events from the message bus
            result = rx.recv() => {
                match result {
                    Ok(event) => {
                        // Pass the event through the enrichment pipeline
                        let enrichments = state.enricher_pipeline.run(&event, &active_lods).await;
                        
                        // Convert the base event to a Map so we can inject the enrichments
                        let mut event_val = match serde_json::to_value(&event) {
                            Ok(serde_json::Value::Object(map)) => map,
                            _ => continue,
                        };

                        // Inject the top-level keys returned by the enrichers
                        for (k, v) in enrichments {
                            event_val.insert(k, v);
                        }

                        if let Ok(json) = serde_json::to_string(&event_val) {
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

            // 2. Handle incoming messages from the WebSocket client
            client_msg_opt = socket.next() => {
                let msg = match client_msg_opt {
                    Some(Ok(m)) => m,
                    Some(Err(_)) | None => break, // Connection closed or error
                };

                match msg {
                    Message::Text(text) => {
                        match serde_json::from_str::<SubscribeMessage>(&text) {
                            Ok(sub) => {
                                // Validate viewport size
                                let diag_km = viewport_diagonal_km(&sub.viewport);
                                if diag_km > state.max_viewport_km {
                                    warn!(
                                        client_id = short_id,
                                        diagonal_km = format!("{:.1}", diag_km),
                                        max_km = state.max_viewport_km,
                                        "Viewport too large, rejecting"
                                    );
                                    let err = ErrorMessage {
                                        type_: "Error",
                                        message: format!(
                                            "Viewport too large ({:.1} km diagonal). Maximum allowed: {} km.",
                                            diag_km, state.max_viewport_km,
                                        ),
                                    };
                                    if let Ok(json) = serde_json::to_string(&err) {
                                        let _ = socket.send(Message::Text(json.into())).await;
                                    }
                                    continue;
                                }

                                // Update active lods
                                active_lods = sub.lod.clone();
                                
                                let lod_str = if sub.lod.is_empty() {
                                    "vessels only".to_string()
                                } else {
                                    sub.lod.iter().map(|l| format!("{:?}", l)).collect::<Vec<_>>().join(", ")
                                };
                                info!(
                                    client_id = short_id,
                                    diagonal_km = format!("{:.1}", diag_km),
                                    lod = lod_str,
                                    "Client viewport updated"
                                );
                                state.broadcaster.update_subscription(
                                    &client_id_str,
                                    Some(sub.viewport),
                                ).await;
                                
                                // Send Ack
                                let ack = SubscribeAck {
                                    type_: "SubscribeAck",
                                    status: "ok",
                                };
                                if let Ok(json) = serde_json::to_string(&ack) {
                                    let _ = socket.send(Message::Text(json.into())).await;
                                }
                            }
                            Err(e) => {
                                // Send Error
                                let err = ErrorMessage {
                                    type_: "Error",
                                    message: format!("Invalid subscription JSON: {}", e),
                                };
                                if let Ok(json) = serde_json::to_string(&err) {
                                    let _ = socket.send(Message::Text(json.into())).await;
                                }
                            }
                        }
                    }
                    Message::Close(_) => break,
                    _ => {} // Ignore Ping/Pong/Binary
                }
            }
        }
    }

    info!(client_id = short_id, "WebSocket client disconnected");
}
