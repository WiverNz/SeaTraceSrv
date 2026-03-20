use anyhow::Result;
use async_trait::async_trait;
use core_model::Event;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use tokio::sync::{broadcast, RwLock};

/// A geographic bounding box representing the client's visible map area.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Viewport {
    pub north: f64,
    pub south: f64,
    pub east: f64,
    pub west: f64,
}

impl Viewport {
    /// Returns `true` if the given (lat, lon) falls within this viewport.
    pub fn contains(&self, lat: f64, lon: f64) -> bool {
        let lat_ok = lat >= self.south && lat <= self.north;
        let lon_ok = if self.west <= self.east {
            // Normal case: viewport does not cross the antimeridian
            lon >= self.west && lon <= self.east
        } else {
            // Viewport crosses the antimeridian (e.g. west=170, east=-170)
            lon >= self.west || lon <= self.east
        };
        lat_ok && lon_ok
    }
}

#[async_trait]
pub trait Broadcaster: Send + Sync {
    /// Connect a client. Returns a channel (stream) for sending events.
    async fn subscribe(&self, client_id: &str, viewport: Option<Viewport>) -> broadcast::Receiver<Event>;

    /// Update an existing client's viewport without recreating the channel.
    async fn update_subscription(&self, client_id: &str, viewport: Option<Viewport>);

    /// Broadcast an event to all clients whose viewport contains the event location.
    async fn broadcast(&self, event: Event) -> Result<()>;
}

/// Extract (lat, lon) from an event by serializing the payload and reading
/// the `lat`/`lon` fields. Works for all payload types (VesselPosition,
/// SeaPhenomenon, WeatherAlert, Incident).
fn event_location(event: &Event) -> Option<(f64, f64)> {
    let val = serde_json::to_value(&event.payload).ok()?;
    let lat = val.get("lat")?.as_f64()?;
    let lon = val.get("lon")?.as_f64()?;
    Some((lat, lon))
}

/// Simple in-memory broadcaster for local development and tests.
#[derive(Debug)]
pub struct InMemoryBroadcaster {
    /// Broadcast channel per client.
    clients: RwLock<HashMap<String, broadcast::Sender<Event>>>,
    /// Viewport per client. `None` means the client has not subscribed yet.
    viewports: RwLock<HashMap<String, Viewport>>,
}

impl InMemoryBroadcaster {
    pub fn new() -> Self {
        Self {
            clients: RwLock::new(HashMap::new()),
            viewports: RwLock::new(HashMap::new()),
        }
    }
}

impl Default for InMemoryBroadcaster {
    fn default() -> Self {
        Self::new()
    }
}

#[async_trait]
impl Broadcaster for InMemoryBroadcaster {
    async fn subscribe(&self, client_id: &str, viewport: Option<Viewport>) -> broadcast::Receiver<Event> {
        let rx = {
            let mut clients = self.clients.write().await;
            let (tx, rx) = broadcast::channel(1024);
            clients.insert(client_id.to_string(), tx);
            rx
        };

        if let Some(vp) = viewport {
            let mut vps = self.viewports.write().await;
            vps.insert(client_id.to_string(), vp);
        }

        rx
    }

    async fn update_subscription(&self, client_id: &str, viewport: Option<Viewport>) {
        let mut vps = self.viewports.write().await;
        match viewport {
            Some(vp) => {
                vps.insert(client_id.to_string(), vp);
            }
            None => {
                vps.remove(client_id);
            }
        }
    }

    async fn broadcast(&self, event: Event) -> Result<()> {
        let (lat, lon) = match event_location(&event) {
            Some(loc) => loc,
            None => return Ok(()), // No coordinates — nothing to route
        };

        let vps = self.viewports.read().await;
        let clients = self.clients.read().await;

        for (client_id, viewport) in vps.iter() {
            if viewport.contains(lat, lon) {
                if let Some(tx) = clients.get(client_id) {
                    // Ignore send errors (channel may be closed if client disconnected)
                    let _ = tx.send(event.clone());
                }
            }
        }

        Ok(())
    }
}
