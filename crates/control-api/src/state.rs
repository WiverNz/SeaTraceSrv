use crate::enrichment::EnrichmentPipeline;
use crate::weather::WeatherClient;
use delivery::Broadcaster;
use std::sync::Arc;
use tokio::sync::RwLock;

pub type RedisPool = bb8::Pool<bb8_redis::RedisConnectionManager>;

#[derive(Clone)]
pub struct AppState {
    pub broadcaster: Arc<dyn Broadcaster>,
    pub enricher_pipeline: EnrichmentPipeline,
    pub redis_pool: RedisPool,
    pub active_catalog_version: Arc<RwLock<Option<String>>>,
}

impl AppState {
    pub fn new(
        broadcaster: Arc<dyn Broadcaster>,
        redis_pool: RedisPool,
        active_catalog_version: Arc<RwLock<Option<String>>>,
    ) -> Self {
        let weather_client = WeatherClient::new();
        let enricher_pipeline = EnrichmentPipeline::new().with(weather_client);

        Self {
            broadcaster,
            enricher_pipeline,
            redis_pool,
            active_catalog_version,
        }
    }
}
