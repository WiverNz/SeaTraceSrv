/// GEBCO (General Bathymetric Chart of the Oceans) downloader.
///
/// GEBCO publishes annual global bathymetry grids and sub-ice topography data.
/// Downloads are listed at:
///   https://www.gebco.net/data_and_products/gridded_bathymetry_data/
///
/// # How the scraper works (to be implemented)
///
/// 1. Fetch the GEBCO downloads page HTML.
/// 2. Parse all `<a href="...">` links matching `*.zip` or `*.nc` patterns.
/// 3. Filter to relevant products:
///    - GEBCO_<year>_sub_ice_topo.zip   (sub-ice topo, ~1 GB)
///    - GEBCO_<year>.zip                (standard grid)
///    - GEBCO_<year>_TID.zip            (type identifier grid)
/// 4. Download each file into `data_dir/gebco/`.
/// 5. Return a `ManifestEntry` per file.
///
/// # Status
///
/// Stub — link discovery and download are not yet implemented.
use crate::maps::{ManifestEntry, MapSource};
use anyhow::Result;
use async_trait::async_trait;
use chrono::Utc;
use std::path::Path;
use tracing::warn;

const GEBCO_DOWNLOADS_PAGE: &str =
    "https://www.gebco.net/data_and_products/gridded_bathymetry_data/";

pub struct GebcoSource {
    #[allow(dead_code)] // used once HTTP fetch is implemented
    client: reqwest::Client,
}

impl GebcoSource {
    pub fn new(client: reqwest::Client) -> Self {
        Self { client }
    }
}

#[async_trait]
impl MapSource for GebcoSource {
    fn name(&self) -> &str {
        "gebco"
    }

    async fn fetch(&self, data_dir: &Path) -> Result<Vec<ManifestEntry>> {
        // TODO: implement the following steps:
        //
        // 1. GET GEBCO_DOWNLOADS_PAGE
        //    let html = self.client.get(GEBCO_DOWNLOADS_PAGE).send().await?.text().await?;
        //
        // 2. Parse download links with a HTML parser (e.g. `scraper` crate):
        //    look for <a href="..."> where href ends with ".zip" or ".nc"
        //    and the link text or href contains "sub_ice_topo", "GEBCO_", etc.
        //
        // 3. For each discovered link:
        //    - Build a ManifestEntry with downloaded=false.
        //    - Download the file into data_dir:
        //        tokio::fs::create_dir_all(data_dir).await?;
        //        let fname = url_to_filename(&url);
        //        let dest  = data_dir.join(&fname);
        //        stream_download(&self.client, &url, &dest).await?;
        //        entry.downloaded = true;
        //        entry.size_bytes = Some(dest.metadata()?.len());
        //
        // 4. Return the list of ManifestEntry.

        warn!(
            "GebcoSource::fetch is not yet implemented; downloads page: {}",
            GEBCO_DOWNLOADS_PAGE
        );
        tokio::fs::create_dir_all(data_dir).await?;

        Ok(vec![ManifestEntry {
            name: "GEBCO (stub)".to_string(),
            url: GEBCO_DOWNLOADS_PAGE.to_string(),
            local_path: "gebco/".to_string(),
            size_bytes: None,
            downloaded: false,
            fetched_at: Utc::now(),
        }])
    }
}
