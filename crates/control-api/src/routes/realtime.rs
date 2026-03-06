use axum::{
    extract::{
        ws::{Message, WebSocket, WebSocketUpgrade},
        State,
    },
    response::IntoResponse,
};
use futures_util::stream::StreamExt;
use serde::{Deserialize, Serialize};

use crate::{lod::Lod, state::AppState};
use tracing::info;

/// Message sent by the client to establish or update a subscription.
#[derive(Debug, Deserialize)]
pub struct SubscribeMessage {
    pub h3_cells: Vec<u64>,
    #[serde(default)]
    pub lod: Vec<Lod>,
}

#[derive(Serialize)]
struct SubscribeAck {
    #[serde(rename = "type")]
    type_: &'static str,
    status: &'static str,
    active_cells: usize,
}

#[derive(Serialize)]
struct ErrorMessage {
    #[serde(rename = "type")]
    type_: &'static str,
    message: String,
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
    let mut rx = state.broadcaster.subscribe(&client_id_str, vec![]).await;
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
                                // Update active lods
                                active_lods = sub.lod.clone();
                                
                                // Update subscription in the broadcaster
                                let cells_count = sub.h3_cells.len();
                                let lod_str = if sub.lod.is_empty() {
                                    "vessels only".to_string()
                                } else {
                                    sub.lod.iter().map(|l| format!("{:?}", l)).collect::<Vec<_>>().join(", ")
                                };
                                info!(
                                    client_id = short_id,
                                    cells = if cells_count == 0 { "wildcard".to_string() } else { cells_count.to_string() },
                                    lod = lod_str,
                                    "Client subscribed"
                                );
                                state.broadcaster.update_subscription(&client_id_str, sub.h3_cells).await;
                                
                                // Send Ack
                                let ack = SubscribeAck {
                                    type_: "SubscribeAck",
                                    status: "ok",
                                    active_cells: cells_count,
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
