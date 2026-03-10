/// Map and chart data downloader.
///
/// This module fetches nautical chart and bathymetry data from public sources,
/// organises them under `data_dir`, and writes a `manifest.json` describing
/// every downloaded file.
///
/// Supported sources (see sub-modules):
///   - [`gebco`]    — GEBCO global bathymetry / sub-ice topo grids
///   - [`noaa_enc`] — NOAA Electronic Navigational Charts (ENC), catalog XML
///                    and pre-packaged ZIP bundles
///   - [`emodnet`]  — EMODnet Bathymetry WCS/WMS/download service links
pub mod emodnet;
pub mod gebco;
pub mod noaa_enc;

use anyhow::Result;
use async_trait::async_trait;
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};
use tracing::{error, info};

// ── Manifest ──────────────────────────────────────────────────────────────────

/// A single downloaded (or discovered) file entry in the manifest.
#[derive(Debug, Serialize, Deserialize)]
pub struct ManifestEntry {
    pub name: String,
    pub url: String,
    /// Local path relative to `data_dir`.
    pub local_path: String,
    pub size_bytes: Option<u64>,
    pub downloaded: bool,
    pub fetched_at: DateTime<Utc>,
}

/// Top-level manifest written to `<data_dir>/manifest.json`.
#[derive(Debug, Default, Serialize, Deserialize)]
pub struct Manifest {
    pub generated_at: Option<DateTime<Utc>>,
    pub sources: ManifestSources,
}

#[derive(Debug, Default, Serialize, Deserialize)]
pub struct ManifestSources {
    pub gebco: Vec<ManifestEntry>,
    pub noaa_enc: Vec<ManifestEntry>,
    pub emodnet: Vec<ManifestEntry>,
}

impl Manifest {
    pub async fn write(&self, data_dir: &Path) -> Result<()> {
        tokio::fs::create_dir_all(data_dir).await?;
        let path = data_dir.join("manifest.json");
        let json = serde_json::to_string_pretty(self)?;
        tokio::fs::write(&path, json).await?;
        info!("manifest written to {}", path.display());
        Ok(())
    }
}

// ── MapSource trait ───────────────────────────────────────────────────────────

/// Implement for each chart/bathymetry data source.
#[async_trait]
pub trait MapSource: Send + Sync {
    fn name(&self) -> &str;

    /// Discover available downloads and return manifest entries.
    /// Implementations should download files into `data_dir/<name>/`.
    async fn fetch(&self, data_dir: &Path) -> Result<Vec<ManifestEntry>>;
}

// ── Orchestrator ─────────────────────────────────────────────────────────────

/// Run all map sources and produce a combined manifest.
pub async fn run_all(data_dir: &Path, sources: &[Box<dyn MapSource>]) -> Manifest {
    let mut manifest = Manifest {
        generated_at: Some(Utc::now()),
        ..Default::default()
    };

    for source in sources {
        let dest: PathBuf = data_dir.join(source.name());
        match source.fetch(&dest).await {
            Ok(entries) => {
                info!("map source {} fetched {} entries", source.name(), entries.len());
                match source.name() {
                    "gebco" => manifest.sources.gebco = entries,
                    "noaa_enc" => manifest.sources.noaa_enc = entries,
                    "emodnet" => manifest.sources.emodnet = entries,
                    other => {
                        // Future sources — entries are logged but not yet persisted.
                        info!("unknown map source '{}' returned {} entries", other, entries.len());
                    }
                }
            }
            Err(e) => {
                error!("map source {} failed: {:#}", source.name(), e);
            }
        }
    }

    if let Err(e) = manifest.write(data_dir).await {
        error!("failed to write manifest: {:#}", e);
    }

    manifest
}
