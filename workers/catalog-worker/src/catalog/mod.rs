pub mod redis_writer;

use crate::sources::{VesselRecord, VesselSource};
use anyhow::Result;
use redis_writer::RedisPool;
use std::collections::HashMap;
use tracing::{error, info, warn};

const PIPELINE_BATCH_SIZE: usize = 500;

pub struct CatalogBuilder {
    pool: RedisPool,
    sources: Vec<Box<dyn VesselSource>>,
    versions_to_keep: usize,
}

impl CatalogBuilder {
    pub fn new(pool: RedisPool, sources: Vec<Box<dyn VesselSource>>, versions_to_keep: usize) -> Self {
        Self { pool, sources, versions_to_keep }
    }

    /// Run a full catalog build cycle:
    ///
    /// 1. Generate a new version ID (RFC 3339 UTC timestamp).
    /// 2. Write meta with `status=building`.
    /// 3. Fetch records from all sources; merge by MMSI (first source wins per field).
    /// 4. Write records to Redis in pipelined batches.
    /// 5. Update meta to `status=ready`.
    /// 6. Atomically set `vessel_catalog:active_version`.
    /// 7. Register version in the sorted set and prune old versions.
    pub async fn build(&self) -> Result<()> {
        let now = chrono::Utc::now();
        let version = now.to_rfc3339();
        let score = now.timestamp() as f64;
        let source_names = self
            .sources
            .iter()
            .map(|s| s.name())
            .collect::<Vec<_>>()
            .join("+");

        info!("starting catalog build version={}", version);

        redis_writer::write_meta_building(&self.pool, &version, &source_names).await?;

        // ── Fetch ─────────────────────────────────────────────────────────────
        let mut records: HashMap<i64, VesselRecord> = HashMap::new();
        for source in &self.sources {
            match source.fetch().await {
                Ok(fetched) => {
                    info!("source {} returned {} records", source.name(), fetched.len());
                    for record in fetched {
                        records
                            .entry(record.mmsi)
                            .and_modify(|existing| existing.merge_from(&record))
                            .or_insert(record);
                    }
                }
                Err(e) => {
                    warn!("source {} fetch failed: {:#}", source.name(), e);
                }
            }
        }
        info!("merged {} unique MMSI records", records.len());

        if records.is_empty() {
            let msg = "all sources returned empty datasets; aborting build";
            error!("{}", msg);
            redis_writer::write_meta_failed(&self.pool, &version, msg).await?;
            return Err(anyhow::anyhow!(msg));
        }

        // ── Write ─────────────────────────────────────────────────────────────
        redis_writer::write_records(&self.pool, &version, &records, PIPELINE_BATCH_SIZE).await?;

        let completed_at = chrono::Utc::now().to_rfc3339();
        redis_writer::write_meta_ready(&self.pool, &version, records.len(), &completed_at).await?;

        // ── Publish ───────────────────────────────────────────────────────────
        redis_writer::set_active_version(&self.pool, &version).await?;
        redis_writer::register_version(&self.pool, &version, score).await?;
        redis_writer::prune_old_versions(&self.pool, self.versions_to_keep).await?;

        info!("catalog build complete version={}", version);
        Ok(())
    }
}
