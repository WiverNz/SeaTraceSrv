use serde::{Deserialize, Serialize};

pub mod api;


/// Единый формат события в системе
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Event {
    pub event_id: String,
    pub h3_index: u64, // Геокеш (например, разрешение 7 или 8)
    pub timestamp: i64,
    pub source: String,
    pub confidence: f32, // от 0.0 до 1.0
    pub payload: EventPayload,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type")]
pub enum EventPayload {
    VesselPosition {
        mmsi: u32,
        lat: f64,
        lon: f64,
        sog: Option<f32>, // Speed over ground
        cog: Option<f32>, // Course over ground
    },
    WeatherAlert {
        kind: String,
        severity: String,
        polygon: Vec<(f64, f64)>, // Simple array of lat/lon
    },
    SeaPhenomenon {
        kind: String,
        evidence: Option<String>,
        lat: f64,
        lon: f64,
    },
    Incident {
        kind: String,
        vessel_mmsi: Option<u32>,
        lat: f64,
        lon: f64,
    },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum HealthStatus {
    Ok,
    Degraded,
    Down,
}
