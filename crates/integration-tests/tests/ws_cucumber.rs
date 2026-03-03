use cucumber::{given, then, when, World};
use core_model::{Event, EventPayloadVesselPosition};
use delivery::{Broadcaster, InMemoryBroadcaster};
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};
use tokio::net::TcpListener;
use control_api::{create_router, AppState};
use futures_util::{sink::SinkExt, stream::StreamExt};
use tokio_tungstenite::{connect_async, tungstenite::protocol::Message};

#[derive(Debug, Default, World)]
pub struct SeaTraceWsWorld {
    broadcaster: Option<Arc<InMemoryBroadcaster>>,
    server_port: Option<u16>,
    ws_stream: Option<tokio_tungstenite::WebSocketStream<tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>>>,
}

#[given("an Axum server is running with the Control and Realtime APIs")]
async fn start_axum_server(world: &mut SeaTraceWsWorld) {
    let broadcaster = Arc::new(InMemoryBroadcaster::default());
    world.broadcaster = Some(broadcaster.clone());

    let state = AppState::new(broadcaster.clone() as Arc<dyn Broadcaster>);
    let app = create_router(state);

    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    world.server_port = Some(port);

    tokio::spawn(async move {
        axum::serve(listener, app).await.unwrap();
    });
}

#[given("a WebSocket client connects to the server")]
async fn ws_client_connects(world: &mut SeaTraceWsWorld) {
    let port = world.server_port.unwrap();
    let url = format!("ws://127.0.0.1:{}/realtime", port);
    
    let (ws_stream, _) = connect_async(&url).await.expect("Failed to connect");
    world.ws_stream = Some(ws_stream);
}

#[given(expr = "the client sends a subscription message for cell {int}")]
async fn ws_client_subscribes(world: &mut SeaTraceWsWorld, cell: u64) {
    let ws = world.ws_stream.as_mut().unwrap();
    let sub_msg = serde_json::json!({
        "h3_cells": [cell]
    });
    ws.send(Message::Text(sub_msg.to_string())).await.unwrap();
}

#[when(expr = "the system receives a VesselPosition event for cell {int}")]
async fn system_receives_event(world: &mut SeaTraceWsWorld, cell: u64) {
    let _ts = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_millis() as i64;
    
    let payload = EventPayloadVesselPosition {
        type_: core_model::api::types::EventPayloadVesselPositionType::VesselPosition,
        mmsi: 987654321,
        lat: 56.4,
        lon: 38.5,
        sog: None,
        cog: None,
    };
    
    let event = Event {
        event_id: "evt-ws-123".to_string(),
        h3_index: cell,
        timestamp: _ts,
        source: "WS-Test".to_string(),
        confidence: 0.9,
        payload: core_model::api::types::EventPayload::VesselPosition(payload),
    };
    
    let broadcaster = world.broadcaster.as_ref().unwrap();
    broadcaster.broadcast(event).await.unwrap();
}

#[then(expr = "the WebSocket client should receive the event with MMSI {int}")]
async fn ws_client_receives_event(world: &mut SeaTraceWsWorld, expected_mmsi: i64) {
    let ws = world.ws_stream.as_mut().unwrap();
    
    // Ждем 1 сообщение (таймаут чтобы не висеть вечно, если тест сломался)
    let msg = tokio::time::timeout(std::time::Duration::from_secs(2), ws.next())
        .await
        .expect("Timeout waiting for WebSocket message")
        .expect("Stream ended unexpectedly")
        .expect("WebSocket error");

    if let Message::Text(text) = msg {
        let event: Event = serde_json::from_str(&text).expect("Failed to parse event JSON");
        
        match event.payload {
            core_model::api::types::EventPayload::VesselPosition(pos) => {
                assert_eq!(pos.mmsi, expected_mmsi, "MMSI mismatch on websocket");
            }
            _ => panic!("Expected VesselPosition over websocket"),
        }
    } else {
        panic!("Expected Text message over websocket");
    }
}

#[tokio::main]
async fn main() {
    SeaTraceWsWorld::cucumber()
        .run_and_exit("tests/features/ws_api.feature")
        .await;
}
