use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};
use std::{
    collections::HashMap,
    sync::Arc,
    time::{Duration, Instant},
};
use tokio::sync::RwLock;

/// How long a cached weather entry is considered fresh.
const CACHE_TTL: Duration = Duration::from_secs(15 * 60);
const OPEN_METEO_URL: &str = "https://api.open-meteo.com/v1/forecast";

// ── Public types (serialized to the client) ──────────────────────────────────

/// Current weather conditions at a position, from Open-Meteo.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CurrentWeather {
    /// ISO 8601 timestamp of the observation.
    pub time: String,
    /// Air temperature at 2 m (°C).
    pub temperature_2m: f32,
    /// Wind speed at 10 m (km/h).
    pub wind_speed_10m: f32,
    /// Relative humidity at 2 m (%).
    pub relative_humidity_2m: f32,
}

/// Hourly forecast for the next 24 hours, from Open-Meteo.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HourlyWeather {
    /// One ISO 8601 timestamp per hour.
    pub time: Vec<String>,
    /// Air temperature at 2 m per hour (°C).
    pub temperature_2m: Vec<f32>,
    /// Wind speed at 10 m per hour (km/h).
    pub wind_speed_10m: Vec<f32>,
    /// Relative humidity at 2 m per hour (%).
    pub relative_humidity_2m: Vec<f32>,
}

/// Weather data appended to an event when a weather LOD is requested.
#[derive(Debug, Clone, Serialize)]
pub struct WeatherEnrichment {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub current: Option<CurrentWeather>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub hourly: Option<HourlyWeather>,
}

// ── Open-Meteo raw response types (deserialization only) ────────────────────

#[derive(Deserialize)]
struct OpenMeteoResponse {
    current: Option<RawCurrent>,
    hourly: Option<RawHourly>,
}

#[derive(Deserialize)]
struct RawCurrent {
    time: String,
    temperature_2m: f32,
    wind_speed_10m: f32,
    relative_humidity_2m: Option<f32>,
}

#[derive(Deserialize)]
struct RawHourly {
    time: Vec<String>,
    temperature_2m: Vec<f32>,
    wind_speed_10m: Vec<f32>,
    relative_humidity_2m: Vec<f32>,
}

// ── Cache ────────────────────────────────────────────────────────────────────

#[derive(Clone)]
struct CacheEntry {
    current: Option<CurrentWeather>,
    hourly: Option<HourlyWeather>,
    fetched_at: Instant,
}

// ── Client ───────────────────────────────────────────────────────────────────

/// Fetches weather from Open-Meteo and caches results per H3 cell for 15 min.
///
/// The cache key is the H3 cell index so all vessels in the same geographic
/// cell share a single API call and cached response.
#[derive(Clone)]
pub struct WeatherClient {
    http: reqwest::Client,
    cache: Arc<RwLock<HashMap<u64, CacheEntry>>>,
}

impl WeatherClient {
    pub fn new() -> Self {
        Self {
            http: reqwest::Client::new(),
            cache: Arc::new(RwLock::new(HashMap::new())),
        }
    }

    /// Internal fetch logic
    async fn fetch(
        &self,
        h3_cell: u64,
        lat: f64,
        lon: f64,
        need_current: bool,
        need_hourly: bool,
    ) -> Result<WeatherEnrichment> {
        // Fast path: return from cache if still fresh.
        {
            let cache = self.cache.read().await;
            if let Some(entry) = cache.get(&h3_cell) {
                if entry.fetched_at.elapsed() < CACHE_TTL {
                    return Ok(WeatherEnrichment {
                        current: if need_current { entry.current.clone() } else { None },
                        hourly: if need_hourly { entry.hourly.clone() } else { None },
                    });
                }
            }
        }

        // Cache miss or stale: fetch from Open-Meteo.
        let resp = self
            .http
            .get(OPEN_METEO_URL)
            .query(&[
                ("latitude", lat.to_string()),
                ("longitude", lon.to_string()),
                (
                    "current",
                    "temperature_2m,wind_speed_10m,relative_humidity_2m".to_string(),
                ),
                (
                    "hourly",
                    "temperature_2m,relative_humidity_2m,wind_speed_10m".to_string(),
                ),
                ("forecast_days", "1".to_string()),
            ])
            .send()
            .await
            .context("Open-Meteo request failed")?
            .json::<OpenMeteoResponse>()
            .await
            .context("Failed to parse Open-Meteo response")?;

        let current = resp.current.map(|c| CurrentWeather {
            time: c.time,
            temperature_2m: c.temperature_2m,
            wind_speed_10m: c.wind_speed_10m,
            relative_humidity_2m: c.relative_humidity_2m.unwrap_or(0.0),
        });
        let hourly = resp.hourly.map(|h| HourlyWeather {
            time: h.time,
            temperature_2m: h.temperature_2m,
            wind_speed_10m: h.wind_speed_10m,
            relative_humidity_2m: h.relative_humidity_2m,
        });

        {
            let mut cache = self.cache.write().await;
            cache.insert(
                h3_cell,
                CacheEntry {
                    current: current.clone(),
                    hourly: hourly.clone(),
                    fetched_at: Instant::now(),
                },
            );
        }

        Ok(WeatherEnrichment {
            current: if need_current { current } else { None },
            hourly: if need_hourly { hourly } else { None },
        })
    }
}

use crate::enrichment::{extract_position, Enricher};
use core_model::Event;
use crate::lod::Lod;
use futures_util::future::BoxFuture;

impl Enricher for WeatherClient {
    fn name(&self) -> &'static str {
        "weather"
    }

    fn wants(&self, lods: &[Lod]) -> bool {
        lods.contains(&Lod::WeatherCurrent) || lods.contains(&Lod::WeatherHourly)
    }

    fn enrich<'a>(&'a self, event: &'a Event, lods: &'a [Lod]) -> BoxFuture<'a, Option<serde_json::Value>> {
        let (lat, lon) = match extract_position(event) {
            Some(pos) => pos,
            None => return Box::pin(async { None }),
        };
        
        let need_current = lods.contains(&Lod::WeatherCurrent) || lods.contains(&Lod::WeatherHourly);
        let need_hourly = lods.contains(&Lod::WeatherHourly);
        
        Box::pin(async move {
            match self.fetch(event.h3_index, lat, lon, need_current, need_hourly).await {
                Ok(enrichment) => serde_json::to_value(enrichment).ok(),
                Err(e) => {
                    tracing::warn!("Weather fetch failed for h3={}: {:#}", event.h3_index, e);
                    None
                }
            }
        })
    }
}
