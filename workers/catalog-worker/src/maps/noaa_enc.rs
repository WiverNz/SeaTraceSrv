/// NOAA ENC (Electronic Navigational Charts) downloader.
///
/// NOAA distributes free ENC data through the Office of Coast Survey.
/// The catalog XML and pre-packaged ZIP bundles are available at:
///   https://charts.noaa.gov/ENCs/ENCs.shtml
///   https://www.charts.noaa.gov/ENCs/
///
/// # Available package types
///
/// NOAA offers pre-packaged ZIP bundles that group charts by update frequency or geography:
///   - `all`       — complete ENC collection
///   - `one_day`   — charts updated in the last day
///   - `one_week`  — charts updated in the last week
///   - `ten_days`  — charts updated in the last ten days
///   - `state`     — charts grouped by US state
///   - `district`  — charts grouped by USCG district
///   - `region`    — charts grouped by geographic region
///
/// There is also a catalog XML:
///   https://www.charts.noaa.gov/ENCs/ENCProdCat_19115.xml
///
/// # How the downloader works (to be implemented)
///
/// 1. Fetch the catalog XML to discover all individual ENC cells and their metadata.
/// 2. Discover bundle ZIP URLs — these follow predictable patterns or are listed on the HTML page.
/// 3. Download selected bundles into `data_dir/noaa_enc/<package>/`.
/// 4. Return a ManifestEntry per downloaded file.
///
/// # Status
///
/// Stub — HTTP fetch and catalog parsing are not yet implemented.
use crate::maps::{ManifestEntry, MapSource};
use anyhow::Result;
use async_trait::async_trait;
use chrono::Utc;
use std::path::Path;
use tracing::warn;

const NOAA_ENC_PAGE: &str = "https://charts.noaa.gov/ENCs/ENCs.shtml";
const NOAA_ENC_CATALOG_XML: &str =
    "https://www.charts.noaa.gov/ENCs/ENCProdCat_19115.xml";

/// Which pre-packaged bundles to download.
/// Remove entries from this list to skip packages you don't need.
const BUNDLE_TYPES: &[&str] = &[
    "all",
    "one_day",
    "one_week",
    "ten_days",
    "state",
    "district",
    "region",
];

pub struct NoaaEncSource {
    #[allow(dead_code)] // used once HTTP fetch is implemented
    client: reqwest::Client,
}

impl NoaaEncSource {
    pub fn new(client: reqwest::Client) -> Self {
        Self { client }
    }
}

#[async_trait]
impl MapSource for NoaaEncSource {
    fn name(&self) -> &str {
        "noaa_enc"
    }

    async fn fetch(&self, data_dir: &Path) -> Result<Vec<ManifestEntry>> {
        // TODO: implement the following steps:
        //
        // Step 1 — Download the catalog XML:
        //    tokio::fs::create_dir_all(data_dir).await?;
        //    let xml_bytes = self.client.get(NOAA_ENC_CATALOG_XML).send().await?.bytes().await?;
        //    tokio::fs::write(data_dir.join("ENCProdCat_19115.xml"), &xml_bytes).await?;
        //    // Parse with `quick-xml` or `roxmltree` to extract individual cell metadata.
        //
        // Step 2 — Build bundle URLs and download them:
        //    Bundle URL pattern (verify against NOAA_ENC_PAGE):
        //      https://www.charts.noaa.gov/ENCs/<bundle_type>.zip
        //    Example: https://www.charts.noaa.gov/ENCs/all.zip
        //
        //    for bundle in BUNDLE_TYPES {
        //        let url  = format!("https://www.charts.noaa.gov/ENCs/{}.zip", bundle);
        //        let dest = data_dir.join(format!("{}.zip", bundle));
        //        stream_download(&self.client, &url, &dest).await?;
        //        entries.push(ManifestEntry { name: bundle.to_string(), url, ... });
        //    }
        //
        // Step 3 — Optionally scrape the HTML page for any additional links that
        //          don't follow the predictable pattern.

        warn!(
            "NoaaEncSource::fetch is not yet implemented; page: {}",
            NOAA_ENC_PAGE
        );
        tokio::fs::create_dir_all(data_dir).await?;

        let mut entries = vec![ManifestEntry {
            name: "ENCProdCat_19115.xml (stub)".to_string(),
            url: NOAA_ENC_CATALOG_XML.to_string(),
            local_path: "noaa_enc/ENCProdCat_19115.xml".to_string(),
            size_bytes: None,
            downloaded: false,
            fetched_at: Utc::now(),
        }];

        for bundle in BUNDLE_TYPES {
            entries.push(ManifestEntry {
                name: format!("{}.zip (stub)", bundle),
                url: format!("https://www.charts.noaa.gov/ENCs/{}.zip", bundle),
                local_path: format!("noaa_enc/{}.zip", bundle),
                size_bytes: None,
                downloaded: false,
                fetched_at: Utc::now(),
            });
        }

        Ok(entries)
    }
}
