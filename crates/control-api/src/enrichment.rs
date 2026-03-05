use core_model::api::types::EventPayload;
use core_model::Event;
use serde_json::Value;
use std::sync::Arc;
use futures_util::future::BoxFuture;

use crate::lod::Lod;

/// Extracted position from an event payload (if available)
pub fn extract_position(event: &Event) -> Option<(f64, f64)> {
    match &event.payload {
        EventPayload::VesselPosition(p) => Some((p.lat, p.lon)),
        EventPayload::SeaPhenomenon(p) => Some((p.lat, p.lon)),
        EventPayload::Incident(p) => Some((p.lat, p.lon)),
        EventPayload::WeatherAlert(_) => None,
    }
}

/// A trait for appending additional metadata to an event based on requested LODs.
pub trait Enricher: Send + Sync {
    /// The key under which the enrichment will be injected in the JSON
    fn name(&self) -> &'static str;

    /// Checks if this enricher should run for the requested LODs
    fn wants(&self, lods: &[Lod]) -> bool;

    /// Fetches the enrichment data.
    fn enrich<'a>(&'a self, event: &'a Event, lods: &'a [Lod]) -> BoxFuture<'a, Option<Value>>;
}

/// Pipeline that runs multiple enrichers concurrently
#[derive(Default, Clone)]
pub struct EnrichmentPipeline {
    enrichers: Arc<Vec<Box<dyn Enricher>>>,
}

impl EnrichmentPipeline {
    pub fn new() -> Self {
        Self {
            enrichers: Arc::new(Vec::new()),
        }
    }

    pub fn with(mut self, enricher: impl Enricher + 'static) -> Self {
        let enrichers = Arc::get_mut(&mut self.enrichers).unwrap();
        enrichers.push(Box::new(enricher));
        self
    }

    /// Evaluates all active enrichers concurrently and returns the map of results
    pub async fn run(&self, event: &Event, active_lods: &[Lod]) -> serde_json::Map<String, Value> {
        if active_lods.is_empty() {
            return serde_json::Map::new();
        }

        let mut futures = Vec::new();

        for enricher in self.enrichers.iter() {
            if enricher.wants(active_lods) {
                let fut = Box::pin(async move {
                    let result = enricher.enrich(event, active_lods).await;
                    (enricher.name(), result)
                });
                futures.push(fut);
            }
        }

        let results = futures_util::future::join_all(futures).await;
        
        let mut map = serde_json::Map::new();
        for (name, result) in results {
            if let Some(val) = result {
                map.insert(name.to_string(), val);
            }
        }
        
        map
    }
}
