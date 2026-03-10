mod catalog;
mod config;
mod maps;
mod sources;

use anyhow::Result;
use catalog::CatalogBuilder;
use config::Config;
use maps::{
    emodnet::EmodnetSource, gebco::GebcoSource, noaa_enc::NoaaEncSource, MapSource,
};
use sources::{itu::ItuMarsSource, VesselSource};
use std::path::Path;
use tracing::{error, info};
use tracing_subscriber::EnvFilter;

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info")),
        )
        .init();

    let cfg = Config::from_env();

    info!(
        redis_url = %cfg.redis_url,
        refresh_interval_secs = cfg.refresh_interval.as_secs(),
        data_dir = %cfg.data_dir,
        versions_to_keep = cfg.versions_to_keep,
        "catalog-worker starting"
    );

    // ── Redis pool ────────────────────────────────────────────────────────────
    let manager = bb8_redis::RedisConnectionManager::new(cfg.redis_url.as_str())?;
    let pool = bb8::Pool::builder().build(manager).await?;

    // ── HTTP client (shared by all sources) ───────────────────────────────────
    let http = reqwest::Client::builder()
        .user_agent("seatracesrv-catalog-worker/0.1")
        .build()?;

    // ── Vessel catalog sources (add more here as they are implemented) ────────
    let vessel_sources: Vec<Box<dyn VesselSource>> =
        vec![Box::new(ItuMarsSource::new(http.clone()))];

    // ── Map sources ───────────────────────────────────────────────────────────
    let map_sources: Vec<Box<dyn MapSource>> = vec![
        Box::new(GebcoSource::new(http.clone())),
        Box::new(NoaaEncSource::new(http.clone())),
        Box::new(EmodnetSource::new(http.clone())),
    ];

    let data_dir = std::path::PathBuf::from(&cfg.data_dir);

    // ── Build + refresh loop ──────────────────────────────────────────────────
    let catalog = CatalogBuilder::new(pool, vessel_sources, cfg.versions_to_keep);

    loop {
        // Vessel catalog
        if let Err(e) = catalog.build().await {
            error!("catalog build failed: {:#}", e);
        }

        // Map data
        run_maps(&data_dir, &map_sources).await;

        info!(
            "sleeping for {} seconds until next run",
            cfg.refresh_interval.as_secs()
        );
        tokio::time::sleep(cfg.refresh_interval).await;
    }
}

async fn run_maps(data_dir: &Path, sources: &[Box<dyn MapSource>]) {
    info!("starting map data refresh");
    maps::run_all(data_dir, sources).await;
    info!("map data refresh complete");
}
