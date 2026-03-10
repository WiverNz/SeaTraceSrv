/// EMODnet (European Marine Observation and Data Network) bathymetry downloader.
///
/// EMODnet Bathymetry provides high-resolution digital terrain models (DTM) for
/// European sea regions and publishes OGC web services (WCS, WMS) alongside direct
/// download links. The portal is:
///   https://emodnet.ec.europa.eu/en/bathymetry
///
/// # Available interfaces
///
/// EMODnet exposes data through several mechanisms:
///
/// 1. **Direct downloads** — pre-packaged NetCDF / GeoTIFF grids for each sea region:
///    - North Sea, Baltic Sea, Mediterranean Sea, Black Sea, Arctic Ocean, …
///    Download page: https://emodnet.ec.europa.eu/en/bathymetry
///
/// 2. **WCS (Web Coverage Service)**:
///    Endpoint: https://ows.emodnet-bathymetry.eu/wcs
///    Use `GetCapabilities` to enumerate available coverages, then `GetCoverage`
///    for specific extents.
///
/// 3. **WMS (Web Map Service)**:
///    Endpoint: https://ows.emodnet-bathymetry.eu/wms
///    Suitable for map tile rendering, not for raw data extraction.
///
/// # How the scraper works (to be implemented)
///
/// 1. Fetch the WCS GetCapabilities XML:
///    GET https://ows.emodnet-bathymetry.eu/wcs?service=WCS&version=1.0.0&request=GetCapabilities
///    Parse `<CoverageOfferingBrief>` elements to enumerate available layers.
///
/// 2. Optionally scrape the downloads page HTML to collect direct download links
///    that are not exposed through the OGC services (e.g. pre-packaged full-sea-region files).
///
/// 3. For each discovered download:
///    - Record the URL and layer name in a ManifestEntry.
///    - Optionally download into `data_dir/emodnet/<layer_name>.(nc|tif)`.
///
/// # Status
///
/// Stub — WCS interrogation and file download are not yet implemented.
use crate::maps::{ManifestEntry, MapSource};
use anyhow::Result;
use async_trait::async_trait;
use chrono::Utc;
use std::path::Path;
use tracing::warn;

const EMODNET_PORTAL: &str = "https://emodnet.ec.europa.eu/en/bathymetry";
const EMODNET_WCS: &str =
    "https://ows.emodnet-bathymetry.eu/wcs?service=WCS&version=1.0.0&request=GetCapabilities";
const EMODNET_WMS: &str =
    "https://ows.emodnet-bathymetry.eu/wms?service=WMS&version=1.3.0&request=GetCapabilities";

pub struct EmodnetSource {
    #[allow(dead_code)] // used once HTTP fetch is implemented
    client: reqwest::Client,
}

impl EmodnetSource {
    pub fn new(client: reqwest::Client) -> Self {
        Self { client }
    }
}

#[async_trait]
impl MapSource for EmodnetSource {
    fn name(&self) -> &str {
        "emodnet"
    }

    async fn fetch(&self, data_dir: &Path) -> Result<Vec<ManifestEntry>> {
        // TODO: implement the following steps:
        //
        // Step 1 — WCS GetCapabilities:
        //    let xml = self.client.get(EMODNET_WCS).send().await?.text().await?;
        //    // Parse with `quick-xml`: extract <Identifier> elements to get
        //    // the list of available coverage IDs (sea regions / resolutions).
        //    // Build a ManifestEntry per coverage with:
        //    //   url = format!("{}?service=WCS&version=1.0.0&request=GetCoverage&...", base)
        //
        // Step 2 — HTML scrape for direct downloads:
        //    let html = self.client.get(EMODNET_PORTAL).send().await?.text().await?;
        //    // Use `scraper` crate: CSS selector `a[href$=".nc"], a[href$=".tif"]`
        //    // to collect direct NetCDF / GeoTIFF download URLs.
        //
        // Step 3 — (Optional) download files:
        //    tokio::fs::create_dir_all(data_dir).await?;
        //    for entry in &mut entries {
        //        let dest = data_dir.join(&entry.name);
        //        stream_download(&self.client, &entry.url, &dest).await?;
        //        entry.downloaded = true;
        //    }

        warn!(
            "EmodnetSource::fetch is not yet implemented; portal: {}",
            EMODNET_PORTAL
        );
        tokio::fs::create_dir_all(data_dir).await?;

        Ok(vec![
            ManifestEntry {
                name: "emodnet_wcs_capabilities (stub)".to_string(),
                url: EMODNET_WCS.to_string(),
                local_path: "emodnet/wcs_capabilities.xml".to_string(),
                size_bytes: None,
                downloaded: false,
                fetched_at: Utc::now(),
            },
            ManifestEntry {
                name: "emodnet_wms_capabilities (stub)".to_string(),
                url: EMODNET_WMS.to_string(),
                local_path: "emodnet/wms_capabilities.xml".to_string(),
                size_bytes: None,
                downloaded: false,
                fetched_at: Utc::now(),
            },
        ])
    }
}
