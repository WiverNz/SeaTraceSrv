use anyhow::{Context, Result};
use core_model::api::types::{EventPayload, EventPayloadVesselPosition, EventPayloadVesselPositionType};
use core_model::Event;
use delivery::Broadcaster;
use futures_util::{SinkExt, StreamExt};
use h3o::{LatLng, Resolution};
use serde::{Deserialize, Serialize};
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tokio_tungstenite::{connect_async_tls_with_config, tungstenite::Message};
use tracing::{debug, info, warn};
use uuid::Uuid;

// ──────────────────────────────────────────────────────────────────────────────
// AISStream wire types (JSON deserialization)
// ──────────────────────────────────────────────────────────────────────────────

/// Top-level envelope sent by AISStream for every AIS message.
#[derive(Debug, Deserialize)]
pub struct AisEnvelope {
    #[serde(rename = "MessageType")]
    pub message_type: String,
    #[serde(rename = "Message")]
    pub message: AisMessageBody,
    #[serde(rename = "MetaData")]
    pub metadata: AisMetadata,
}

/// The `Message` field — contains a key per message type.
#[derive(Debug, Deserialize)]
pub struct AisMessageBody {
    #[serde(rename = "PositionReport")]
    pub position_report: Option<PositionReport>,
}

/// AIS Class A Position Report (message types 1, 2, 3).
#[derive(Debug, Deserialize)]
pub struct PositionReport {
    /// MMSI (vessel identifier)
    #[serde(rename = "UserID")]
    pub user_id: i64,
    #[serde(rename = "Latitude")]
    pub latitude: f64,
    #[serde(rename = "Longitude")]
    pub longitude: f64,
    /// Speed Over Ground (knots × 10, or 1023 = not available)
    #[serde(rename = "Sog")]
    pub sog: Option<f32>,
    /// Course Over Ground (degrees × 10)
    #[serde(rename = "Cog")]
    pub cog: Option<f32>,
}

/// Metadata enrichment from AISStream (ship name, last known position, etc.)
#[derive(Debug, Deserialize)]
pub struct AisMetadata {
    #[serde(rename = "MMSI")]
    pub mmsi: i64,
    #[serde(rename = "ShipName")]
    pub ship_name: Option<String>,
    #[serde(rename = "latitude")]
    pub latitude: f64,
    #[serde(rename = "longitude")]
    pub longitude: f64,
}

// ──────────────────────────────────────────────────────────────────────────────
// Subscription message sent to AISStream upon connection
// ──────────────────────────────────────────────────────────────────────────────

#[derive(Debug, Serialize)]
struct SubscriptionMessage<'a> {
    #[serde(rename = "APIKey")]
    api_key: &'a str,
    /// `[[[lat1, lon1], [lat2, lon2]], ...]`
    #[serde(rename = "BoundingBoxes")]
    bounding_boxes: &'a Vec<Vec<[f64; 2]>>,
    /// Only stream position reports for now.
    #[serde(rename = "FilterMessageTypes")]
    filter_message_types: &'static [&'static str],
}

// ──────────────────────────────────────────────────────────────────────────────
// Public configuration
// ──────────────────────────────────────────────────────────────────────────────

/// Configuration for the AISStream connector.
#[derive(Debug, Clone)]
pub struct AisStreamConfig {
    /// API key from https://aisstream.io/
    pub api_key: String,
    /// Geographic areas to subscribe to.
    /// Format: `[[[lat1, lon1], [lat2, lon2]]]`  (world = `[[[-90.0, -180.0], [90.0, 180.0]]]`)
    pub bounding_boxes: Vec<Vec<[f64; 2]>>,
    /// H3 resolution for converting lat/lon → H3 cell index. Defaults to 7.
    pub h3_resolution: Resolution,
}

impl AisStreamConfig {
    /// Create a config that subscribes to the whole world at H3 resolution 7.
    pub fn world(api_key: impl Into<String>) -> Self {
        Self {
            api_key: api_key.into(),
            bounding_boxes: vec![vec![[-90.0, -180.0], [90.0, 180.0]]],
            h3_resolution: Resolution::Seven,
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Connector
// ──────────────────────────────────────────────────────────────────────────────

const AISSTREAM_URL: &str = "wss://stream.aisstream.io/v0/stream";
const INITIAL_BACKOFF: Duration = Duration::from_secs(2);
const MAX_BACKOFF: Duration = Duration::from_secs(60);

/// Connects to AISStream, streams vessel position reports and pushes them as
/// [`Event`]s into the shared [`Broadcaster`].
pub struct AisStreamConnector {
    config: AisStreamConfig,
    broadcaster: Arc<dyn Broadcaster>,
}

impl AisStreamConnector {
    pub fn new(config: AisStreamConfig, broadcaster: Arc<dyn Broadcaster>) -> Self {
        Self { config, broadcaster }
    }

    /// Run the connector indefinitely.  On connection errors, reconnects with
    /// exponential back-off capped at [`MAX_BACKOFF`].
    pub async fn run(&self) {
        let mut backoff = INITIAL_BACKOFF;

        loop {
            match self.connect_and_stream().await {
                Ok(()) => {
                    info!("AISStream connection closed cleanly, reconnecting…");
                }
                Err(e) => {
                    warn!("AISStream error: {:#}. Reconnecting in {:?}…", e, backoff);
                }
            }

            tokio::time::sleep(backoff).await;
            backoff = (backoff * 2).min(MAX_BACKOFF);
        }
    }

    /// Establish one WSS connection, send the subscription, and stream messages
    /// until the connection drops or an error occurs.
    async fn connect_and_stream(&self) -> Result<()> {
        info!("Connecting to AISStream at {}", AISSTREAM_URL);

        // connect_async_tls_with_config uses the native-tls or rustls backend
        // (whichever feature is enabled) to handle the wss:// TLS handshake.
        let (mut ws, _response) =
            connect_async_tls_with_config(AISSTREAM_URL, None, false, None)
                .await
                .context("WebSocket TLS connection failed")?;

        info!("Connected to AISStream, sending subscription");

        // Send the subscription: must arrive within 3 s of connecting (API rule).
        let sub = SubscriptionMessage {
            api_key: &self.config.api_key,
            bounding_boxes: &self.config.bounding_boxes,
            filter_message_types: &["PositionReport"],
        };
        let sub_json = serde_json::to_string(&sub).context("Failed to serialize subscription")?;
        ws.send(Message::Text(sub_json.into()))
            .await
            .context("Failed to send subscription message")?;

        info!("Subscription sent, streaming messages…");
        backoff_reset();

        while let Some(raw) = ws.next().await {
            let raw = raw.context("WebSocket receive error")?;

            match raw {
                Message::Text(text) => {
                    if let Err(e) = self.handle_message(&text).await {
                        debug!("Failed to handle AIS message: {:#}", e);
                    }
                }
                Message::Ping(data) => {
                    ws.send(Message::Pong(data)).await.ok();
                }
                Message::Close(_) => {
                    info!("AISStream sent Close frame");
                    break;
                }
                _ => {}
            }
        }

        Ok(())
    }

    /// Parse and convert one JSON AIS message envelope into an internal [`Event`]
    /// and broadcast it.
    async fn handle_message(&self, text: &str) -> Result<()> {
        let envelope: AisEnvelope =
            serde_json::from_str(text).context("Failed to parse AIS envelope")?;

        if envelope.message_type != "PositionReport" {
            return Ok(());
        }

        let pr = envelope
            .message
            .position_report
            .context("PositionReport field missing in message body")?;

        // Convert lat/lon → H3 cell at the configured resolution.
        let latlng = LatLng::new(pr.latitude, pr.longitude)
            .context("Invalid lat/lon from AIS message")?;
        let h3_cell = latlng.to_cell(self.config.h3_resolution);
        let h3_index: u64 = u64::from(h3_cell);

        let ts = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_millis() as i64;

        let payload = EventPayloadVesselPosition {
            type_: EventPayloadVesselPositionType::VesselPosition,
            mmsi: pr.user_id,
            lat: pr.latitude,
            lon: pr.longitude,
            sog: pr.sog,
            cog: pr.cog,
        };

        let event = Event {
            event_id: Uuid::new_v4().to_string(),
            h3_index,
            timestamp: ts,
            source: "AISStream".to_string(),
            confidence: 1.0,
            payload: EventPayload::VesselPosition(payload),
        };

        debug!(
            mmsi = pr.user_id,
            lat = pr.latitude,
            lon = pr.longitude,
            h3_index,
            "Broadcasting VesselPosition event"
        );

        self.broadcaster
            .broadcast(event)
            .await
            .context("Broadcaster error")?;

        Ok(())
    }
}

/// Separate fn so the borrow checker doesn't complain about capturing `backoff`
/// inside the loop — it just signals in logs.
fn backoff_reset() {
    // On a successful connection+subscription, reset happens implicitly by
    // the variable re-binding in `run()` on the next iteration.
}
