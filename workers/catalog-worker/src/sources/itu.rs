/// ITU MARS (Maritime Mobile Access and Retrieval System) vessel source.
///
/// The ITU publishes official MMSI assignments for ship stations as part of the
/// Maritime Mobile Service (MMS) database. The dataset is available at:
///   https://www.itu.int/en/ITU-R/terrestrial/mars/
///
/// The published file is an Excel workbook. The relevant sheet is
/// "Ship Station" and contains columns such as:
///   - MMSI / Call sign
///   - Name of ship
///   - Flag country
///   - Ship type
///
/// # How to implement
///
/// 1. Fetch the workbook URL from the MARS downloads page (it changes periodically).
/// 2. Download the file and parse it with a crate such as `calamine`.
/// 3. Normalize each row into a `VesselRecord`.
///
/// # Status
///
/// Stub — HTTP fetch and Excel parsing are not yet implemented.
/// To add a new working source, implement `VesselSource::fetch` below.
use crate::sources::{VesselRecord, VesselSource};
use anyhow::Result;
use async_trait::async_trait;
use tracing::{info, warn};

/// Base URL for the ITU MARS ship-station export page.
const ITU_MARS_PAGE: &str = "https://www.itu.int/en/ITU-R/terrestrial/mars/Pages/MARS-BS.aspx";

pub struct ItuMarsSource {
    #[allow(dead_code)] // used once HTTP fetch is implemented
    client: reqwest::Client,
}

impl ItuMarsSource {
    pub fn new(client: reqwest::Client) -> Self {
        Self { client }
    }
}

#[async_trait]
impl VesselSource for ItuMarsSource {
    fn name(&self) -> &str {
        "itu_mars"
    }

    async fn fetch(&self) -> Result<Vec<VesselRecord>> {
        // TODO: implement the following steps:
        //
        // 1. GET ITU_MARS_PAGE, extract the current Excel download URL
        //    (look for an <a href> pointing to a .xls or .xlsx file).
        //
        // 2. Download the workbook:
        //    let bytes = self.client.get(&download_url).send().await?.bytes().await?;
        //
        // 3. Parse with `calamine`:
        //    use calamine::{open_workbook_from_rs, Xlsx, Reader};
        //    let workbook = open_workbook_from_rs::<Xlsx<_>, _>(std::io::Cursor::new(bytes))?;
        //    let sheet = workbook.worksheet_range("Ship Stations")?;
        //
        // 4. Map each row to a VesselRecord (see field mapping below).
        //
        // ITU MARS column layout (approximate, verify against the actual workbook):
        //   A: "Administration"  -> flag_name
        //   B: "Call sign"       -> callsign
        //   C: "MMSI"            -> mmsi
        //   D: "Name of station" -> name
        //   E: "Category"        -> type_name
        //   ...
        //
        // 5. Return the Vec<VesselRecord>.

        warn!("ItuMarsSource::fetch is not yet implemented; returning empty dataset");
        info!("ITU MARS page: {}", ITU_MARS_PAGE);
        Ok(vec![])
    }
}

/// Normalize a vessel name: trim whitespace, collapse inner spaces, uppercase.
#[allow(dead_code)] // used by the fetch implementation once written
pub fn normalize_name(name: &str) -> String {
    name.split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
        .to_uppercase()
}
