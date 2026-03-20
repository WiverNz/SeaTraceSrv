//! End-to-end integration test with real AISStream data.
//!
//! This test:
//! 1. Starts the full service with AisStreamConnector connected to AISStream.io
//! 2. Connects a WebSocket client subscribing to busy shipping areas
//! 3. Verifies that real vessel position events arrive
//!
//! Requires `AISSTREAM_API_KEY` environment variable to be set.
//! Run with: cargo test -p integration-tests --test e2e_real_data -- --ignored

use connectors::{AisStreamConfig, AisStreamConnector};
use control_api::{create_app_state, create_router};
use core_model::{api::types::EventPayload, Event};
use delivery::{Broadcaster, InMemoryBroadcaster};
use futures_util::{SinkExt, StreamExt};
use std::sync::Arc;
use std::time::Duration;
use tokio::net::TcpListener;
use tokio_tungstenite::{connect_async, tungstenite::protocol::Message};
use tracing_subscriber::EnvFilter;

fn init_tracing() {
    let _ = tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info")),
        )
        .try_init();
}

#[tokio::test]
#[ignore] // Requires AISSTREAM_API_KEY - run with: cargo test --test e2e_real_data -- --ignored
async fn test_real_aisstream_data_e2e() {
    // Check for API key
    let api_key = match std::env::var("AISSTREAM_API_KEY") {
        Ok(key) if !key.is_empty() && key != "your_api_key_here" => key,
        _ => {
            eprintln!("Skipping test: AISSTREAM_API_KEY not set or invalid");
            eprintln!("Set AISSTREAM_API_KEY environment variable to run this test");
            return;
        }
    };

    // Initialize tracing for debug output
    init_tracing();

    println!("Starting end-to-end test with real AISStream data...");

    // Create shared broadcaster
    let broadcaster: Arc<dyn Broadcaster> = Arc::new(InMemoryBroadcaster::default());

    // Create AISStream connector with world coverage
    let ais_config = AisStreamConfig::world(api_key);
    let connector = AisStreamConnector::new(ais_config, broadcaster.clone());

    // Start Axum server
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    println!("Test server listening on port {}", port);

    let state = create_app_state(broadcaster.clone(), "redis://127.0.0.1:6379", 1_000_000.0)
        .await
        .expect("Failed to create AppState");
    let router = create_router(state);

    // Spawn the server
    tokio::spawn(async move {
        axum::serve(listener, router).await.unwrap();
    });

    // Spawn the AISStream connector
    let connector_handle = tokio::spawn(async move {
        connector.run().await;
    });

    // Give the connector time to establish connection
    println!("Waiting for AISStream connection to establish...");
    tokio::time::sleep(Duration::from_secs(3)).await;

    // Connect WebSocket client
    let url = format!("ws://127.0.0.1:{}/realtime", port);
    println!("Connecting WebSocket client to {}", url);

    let (mut ws_stream, _) = connect_async(&url)
        .await
        .expect("Failed to connect WebSocket client");

    // Subscribe to the entire world viewport (E2E test — we don't know which region will
    // have traffic, and max_viewport_km is set to 1_000_000 above so no size rejection).
    println!("Subscribing to world viewport");

    let sub_msg = serde_json::json!({
        "viewport": { "north": 90.0, "south": -90.0, "east": 180.0, "west": -180.0 }
    });
    ws_stream
        .send(Message::Text(sub_msg.to_string()))
        .await
        .expect("Failed to send subscription");

    // Wait for real events with timeout
    let timeout_duration = Duration::from_secs(60);
    println!(
        "Waiting up to {} seconds for real vessel position events...",
        timeout_duration.as_secs()
    );

    let result = tokio::time::timeout(timeout_duration, async {
        let mut event_count = 0;
        let target_events = 3; // Wait for at least 3 events to confirm stable connection

        while event_count < target_events {
            match ws_stream.next().await {
                Some(Ok(Message::Text(text))) => {
                    match serde_json::from_str::<Event>(&text) {
                        Ok(event) => {
                            event_count += 1;
                            println!("\n=== Received real event #{} ===", event_count);
                            println!("Event ID: {}", event.event_id);
                            println!("H3 Index: {}", event.h3_index);
                            println!("Source: {}", event.source);
                            println!("Timestamp: {}", event.timestamp);

                            if let EventPayload::VesselPosition(pos) = &event.payload {
                                println!("MMSI: {}", pos.mmsi);
                                println!("Position: ({}, {})", pos.lat, pos.lon);
                                if let Some(sog) = pos.sog {
                                    println!("Speed Over Ground: {} knots", sog);
                                }
                                if let Some(cog) = pos.cog {
                                    println!("Course Over Ground: {}°", cog);
                                }
                            }
                            println!("================================\n");

                            // Verify event structure
                            assert!(!event.event_id.is_empty(), "Event ID should not be empty");
                            assert!(event.h3_index > 0, "H3 index should be valid");
                            assert_eq!(event.source, "AISStream", "Source should be AISStream");

                            if let EventPayload::VesselPosition(pos) = &event.payload {
                                assert!(pos.mmsi > 0, "MMSI should be positive");
                                assert!(
                                    pos.lat >= -90.0 && pos.lat <= 90.0,
                                    "Latitude should be valid"
                                );
                                assert!(
                                    pos.lon >= -180.0 && pos.lon <= 180.0,
                                    "Longitude should be valid"
                                );
                            } else {
                                panic!("Expected VesselPosition payload");
                            }
                        }
                        Err(e) => {
                            eprintln!("Failed to parse event: {} - raw: {}", e, text);
                        }
                    }
                }
                Some(Ok(Message::Ping(_))) => {
                    // Ignore pings
                }
                Some(Ok(msg)) => {
                    println!("Received non-text message: {:?}", msg);
                }
                Some(Err(e)) => {
                    panic!("WebSocket error: {}", e);
                }
                None => {
                    panic!("WebSocket stream ended unexpectedly");
                }
            }
        }

        event_count
    })
    .await;

    // Clean up
    connector_handle.abort();

    match result {
        Ok(count) => {
            println!(
                "\nSUCCESS: Received {} real vessel position events from AISStream!",
                count
            );
        }
        Err(_) => {
            panic!(
                "TIMEOUT: Did not receive enough events within {} seconds. \
                This could mean:\n\
                - AISStream connection failed (check API key)\n\
                - No vessel traffic in subscribed areas (unlikely)\n\
                - Network issues",
                timeout_duration.as_secs()
            );
        }
    }
}

#[tokio::test]
#[ignore]
async fn test_health_endpoint_with_real_service() {
    // Quick test that health endpoint works with the full stack
    let api_key = match std::env::var("AISSTREAM_API_KEY") {
        Ok(key) if !key.is_empty() && key != "your_api_key_here" => key,
        _ => {
            eprintln!("Skipping test: AISSTREAM_API_KEY not set");
            return;
        }
    };

    let broadcaster: Arc<dyn Broadcaster> = Arc::new(InMemoryBroadcaster::default());
    let ais_config = AisStreamConfig::world(api_key);
    let connector = AisStreamConnector::new(ais_config, broadcaster.clone());

    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();

    let state = create_app_state(broadcaster.clone(), "redis://127.0.0.1:6379", 1_000_000.0)
        .await
        .expect("Failed to create AppState");
    let router = create_router(state);

    tokio::spawn(async move {
        axum::serve(listener, router).await.unwrap();
    });

    let connector_handle = tokio::spawn(async move {
        connector.run().await;
    });

    // Give services time to start
    tokio::time::sleep(Duration::from_secs(2)).await;

    // Test health endpoint
    let client = reqwest::Client::new();
    let resp = client
        .get(format!("http://127.0.0.1:{}/health", port))
        .send()
        .await
        .expect("Health request failed");

    assert!(resp.status().is_success(), "Health endpoint should return 200");

    let body: serde_json::Value = resp.json().await.expect("Failed to parse health response");
    println!("Health response: {}", serde_json::to_string_pretty(&body).unwrap());

    assert_eq!(body["status"], "ok");

    connector_handle.abort();
}
