use crate::enrichment::EnrichmentPipeline;
use crate::weather::WeatherClient;
use delivery::Broadcaster;
use std::sync::Arc;

#[derive(Clone)]
pub struct AppState {
    pub broadcaster: Arc<dyn Broadcaster>,
    pub enricher_pipeline: EnrichmentPipeline,
}

impl AppState {
    pub fn new(broadcaster: Arc<dyn Broadcaster>) -> Self {
        let weather_client = WeatherClient::new();
        let enricher_pipeline = EnrichmentPipeline::new().with(weather_client);

        Self {
            broadcaster,
            enricher_pipeline,
        }
    }
}
