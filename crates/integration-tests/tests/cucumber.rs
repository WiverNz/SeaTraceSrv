use cucumber::{given, then, when, World};
use core_model::{Event, EventPayloadVesselPosition};
use delivery::{Broadcaster, InMemoryBroadcaster};
use std::time::{SystemTime, UNIX_EPOCH};

#[derive(Debug, Default, World)]
pub struct SeaTraceWorld {
    broadcaster: InMemoryBroadcaster,
    client_rx: Option<tokio::sync::broadcast::Receiver<Event>>,
    received_events: Vec<Event>,
}

#[given("an InMemoryBroadcaster is running")]
async fn start_broadcaster(_world: &mut SeaTraceWorld) {
    // Already started by Default
}

#[given(expr = "a client {string} subscribes to H3 cell {int}")]
async fn subscribe_to_cell(world: &mut SeaTraceWorld, client_id: String, h3_cell: i64) {
    // В OpenAPI h3_index мы переделали на u64, так что скастим
    let rx = world.broadcaster.subscribe(&client_id, vec![h3_cell as u64]).await;
    world.client_rx = Some(rx);
}

#[when(expr = "the system receives a VesselPosition event for cell {int}")]
async fn system_receives_vessel_event(world: &mut SeaTraceWorld, h3_cell: i64) {
    let ts = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_millis() as i64;
        
    let payload = EventPayloadVesselPosition {
        type_: core_model::api::types::EventPayloadVesselPositionType::VesselPosition,
        mmsi: 123456789,
        lat: 55.4,
        lon: 37.5,
        sog: None,
        cog: None,
    };
    
    let event = Event {
        event_id: "evt-123".to_string(),
        h3_index: h3_cell as u64,
        timestamp: ts,
        source: "AISStream-Test".to_string(),
        confidence: 0.9,
        payload: core_model::api::types::EventPayload::VesselPosition(payload),
    };
    
    world.broadcaster.broadcast(event).await.unwrap();
}

#[then(expr = "the client {string} should receive {int} event in the real-time stream")]
async fn client_receives_event(world: &mut SeaTraceWorld, _client_id: String, count: usize) {
    let rx = world.client_rx.as_mut().expect("Client missing receiver");
    
    let mut received = 0;
    for _ in 0..count {
        if let Ok(event) = rx.try_recv() {
            world.received_events.push(event);
            received += 1;
        }
    }
    
    assert_eq!(received, count, "Client did not receive the expected number of events");
}

#[then(expr = "the received event should have mmsi {int}")]
async fn verify_mmsi(world: &mut SeaTraceWorld, expected_mmsi: i64) {
    assert!(!world.received_events.is_empty(), "No events received to verify");
    
    let event = &world.received_events[0];
    
    match &event.payload {
        core_model::api::types::EventPayload::VesselPosition(pos) => {
            assert_eq!(pos.mmsi, expected_mmsi, "MMSI mismatch");
        }
        _ => panic!("Expected VesselPosition event payload"),
    }
}

#[tokio::main]
async fn main() {
    SeaTraceWorld::cucumber()
        .run_and_exit("tests/features/")
        .await;
}
