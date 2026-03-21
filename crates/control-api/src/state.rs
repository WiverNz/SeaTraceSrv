use crate::enrichment::EnrichmentPipeline;
use crate::weather::WeatherClient;
use delivery::Broadcaster;
use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::RwLock;

pub type RedisPool = bb8::Pool<bb8_redis::RedisConnectionManager>;

#[derive(Clone)]
pub struct AppState {
    pub broadcaster: Arc<dyn Broadcaster>,
    pub enricher_pipeline: EnrichmentPipeline,
    pub redis_pool: RedisPool,
    pub active_catalog_version: Arc<RwLock<Option<String>>>,
    /// Maximum viewport diagonal in kilometres. Subscriptions exceeding this
    /// limit are rejected by the WebSocket handler.
    pub max_viewport_km: f64,
    /// In-memory cache of MMSI → vessel name looked up from the Redis catalog.
    /// `None` means the MMSI is not in the catalog. Cleared when the active
    /// catalog version rotates.
    pub vessel_name_cache: Arc<RwLock<HashMap<i64, Option<String>>>>,
}

impl AppState {
    pub fn new(
        broadcaster: Arc<dyn Broadcaster>,
        redis_pool: RedisPool,
        active_catalog_version: Arc<RwLock<Option<String>>>,
        max_viewport_km: f64,
    ) -> Self {
        let weather_client = WeatherClient::new();
        let enricher_pipeline = EnrichmentPipeline::new().with(weather_client);

        Self {
            broadcaster,
            enricher_pipeline,
            redis_pool,
            active_catalog_version,
            max_viewport_km,
            vessel_name_cache: Arc::new(RwLock::new(HashMap::new())),
        }
    }
}
