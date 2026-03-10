use anyhow::Result;
use async_trait::async_trait;

pub mod itu;

/// A single normalized vessel record ready to be written into Redis.
/// All optional fields are populated on a best-effort basis depending on the source.
#[derive(Debug, Clone)]
pub struct VesselRecord {
    pub mmsi: i64,
    pub imo: Option<String>,
    pub name: Option<String>,
    /// Trimmed, uppercased name for internal deduplication.
    pub normalized_name: Option<String>,
    pub callsign: Option<String>,
    /// ISO 3166-1 alpha-2 flag code, e.g. "DK".
    pub flag: Option<String>,
    pub flag_name: Option<String>,
    /// AIS ship type code (0–255).
    pub type_code: Option<String>,
    pub type_name: Option<String>,
    pub subtype: Option<String>,
    pub length_m: Option<f64>,
    pub width_m: Option<f64>,
    pub draft_m: Option<f64>,
    pub year_built: Option<i32>,
    pub gross_tonnage: Option<f64>,
    pub deadweight_t: Option<f64>,
    /// Source identifier, e.g. "itu_mars".
    pub source: String,
    /// RFC 3339 timestamp of when the source last updated this record.
    pub updated_at: String,
}

impl VesselRecord {
    /// Merge `other` into `self`, filling in any `None` fields from `other`.
    /// `self` is assumed to be the primary (higher-priority) source.
    pub fn merge_from(&mut self, other: &VesselRecord) {
        macro_rules! fill {
            ($field:ident) => {
                if self.$field.is_none() {
                    self.$field = other.$field.clone();
                }
            };
        }
        fill!(imo);
        fill!(name);
        fill!(normalized_name);
        fill!(callsign);
        fill!(flag);
        fill!(flag_name);
        fill!(type_code);
        fill!(type_name);
        fill!(subtype);
        fill!(length_m);
        fill!(width_m);
        fill!(draft_m);
        fill!(year_built);
        fill!(gross_tonnage);
        fill!(deadweight_t);
    }
}

/// A pluggable vessel data source.
///
/// Implement this trait for each data provider (ITU MARS, MarineTraffic, GFW, …).
/// The catalog builder iterates all registered sources and merges their records.
#[async_trait]
pub trait VesselSource: Send + Sync {
    /// Short identifier used in logs and in the `source` field of records.
    fn name(&self) -> &str;

    /// Fetch all available vessel records from this source.
    async fn fetch(&self) -> Result<Vec<VesselRecord>>;
}
