/// Writes a versioned vessel catalog into Redis.
///
/// Redis key schema (matches the service-side reader in `control-api`):
///
///   vessel_catalog:active_version          STRING   current active version ID
///   vessel_catalog:versions                ZSET     score=unix-ts, member=version
///   vessel_catalog:version:{v}:meta        HASH     build metadata
///   vessel_catalog:version:{v}:mmsi:{mmsi} HASH     vessel fields
use crate::sources::VesselRecord;
use anyhow::{Context, Result};
use redis::AsyncCommands;
use std::collections::HashMap;
use tracing::{debug, info};

pub type RedisPool = bb8::Pool<bb8_redis::RedisConnectionManager>;

// ── Meta helpers ─────────────────────────────────────────────────────────────

pub async fn write_meta_building(pool: &RedisPool, version: &str, source: &str) -> Result<()> {
    let mut conn = pool.get().await.context("get redis connection")?;
    let key = meta_key(version);
    let _: () = conn
        .hset_multiple(
            &key,
            &[
                ("version", version),
                ("status", "building"),
                ("source", source),
                ("created_at", version), // version IS the timestamp string
            ],
        )
        .await
        .context("write meta:building")?;
    debug!("wrote meta building for version {}", version);
    Ok(())
}

pub async fn write_meta_ready(
    pool: &RedisPool,
    version: &str,
    record_count: usize,
    completed_at: &str,
) -> Result<()> {
    let mut conn = pool.get().await.context("get redis connection")?;
    let key = meta_key(version);
    let count_str = record_count.to_string();
    let _: () = conn
        .hset_multiple(
            &key,
            &[
                ("status", "ready"),
                ("record_count", count_str.as_str()),
                ("completed_at", completed_at),
                ("schema_version", "1"),
            ],
        )
        .await
        .context("write meta:ready")?;
    info!(
        "catalog version {} marked ready ({} records)",
        version, record_count
    );
    Ok(())
}

pub async fn write_meta_failed(pool: &RedisPool, version: &str, reason: &str) -> Result<()> {
    let mut conn = pool.get().await.context("get redis connection")?;
    let key = meta_key(version);
    let _: () = conn
        .hset_multiple(&key, &[("status", "failed"), ("error", reason)])
        .await
        .context("write meta:failed")?;
    Ok(())
}

// ── Vessel records ────────────────────────────────────────────────────────────

/// Write all vessel records for a version using pipelined commands.
/// Batches of `batch_size` HMSETs are sent per pipeline flush.
pub async fn write_records(
    pool: &RedisPool,
    version: &str,
    records: &HashMap<i64, VesselRecord>,
    batch_size: usize,
) -> Result<()> {
    let mut conn = pool.get().await.context("get redis connection")?;

    let mut pipeline = redis::pipe();
    let mut flushed = 0usize;

    for (i, record) in records.values().enumerate() {
        let key = mmsi_key(version, record.mmsi);
        let fields = record_to_fields(record, version);
        pipeline.hset_multiple(&key, &fields);

        if (i + 1) % batch_size == 0 {
            pipeline
                .query_async::<()>(&mut *conn)
                .await
                .context("pipeline flush")?;
            pipeline = redis::pipe();
            flushed += batch_size;
            debug!("flushed {} records", flushed);
        }
    }

    // Flush remaining
    if records.len() % batch_size != 0 {
        pipeline
            .query_async::<()>(&mut *conn)
            .await
            .context("final pipeline flush")?;
    }

    info!("wrote {} vessel records for version {}", records.len(), version);
    Ok(())
}

// ── Version management ────────────────────────────────────────────────────────

/// Atomically set the active catalog version.
pub async fn set_active_version(pool: &RedisPool, version: &str) -> Result<()> {
    let mut conn = pool.get().await.context("get redis connection")?;
    let _: () = conn
        .set("vessel_catalog:active_version", version)
        .await
        .context("SET active_version")?;
    info!("active catalog version → {}", version);
    Ok(())
}

/// Register a version in the sorted set (score = current unix timestamp).
pub async fn register_version(pool: &RedisPool, version: &str, score: f64) -> Result<()> {
    let mut conn = pool.get().await.context("get redis connection")?;
    let _: usize = conn
        .zadd("vessel_catalog:versions", version, score)
        .await
        .context("ZADD versions")?;
    Ok(())
}

/// Remove versions beyond `keep` most recent, deleting all their Redis keys.
pub async fn prune_old_versions(pool: &RedisPool, keep: usize) -> Result<()> {
    let mut conn = pool.get().await.context("get redis connection")?;

    // zrevrange gives newest first; remove everything past index `keep-1`.
    let all_versions: Vec<String> = conn
        .zrevrange("vessel_catalog:versions", 0, -1)
        .await
        .context("ZREVRANGE versions")?;

    if all_versions.len() <= keep {
        return Ok(());
    }

    for old_version in &all_versions[keep..] {
        delete_version(&mut conn, old_version).await?;
        let _: usize = conn
            .zrem("vessel_catalog:versions", old_version.as_str())
            .await
            .context("ZREM old version")?;
        info!("pruned old catalog version {}", old_version);
    }

    Ok(())
}

// ── Private helpers ───────────────────────────────────────────────────────────

fn meta_key(version: &str) -> String {
    format!("vessel_catalog:version:{}:meta", version)
}

fn mmsi_key(version: &str, mmsi: i64) -> String {
    format!("vessel_catalog:version:{}:mmsi:{}", version, mmsi)
}

fn record_to_fields<'a>(r: &'a VesselRecord, version: &'a str) -> Vec<(&'a str, String)> {
    let mut fields: Vec<(&str, String)> = vec![
        ("mmsi", r.mmsi.to_string()),
        ("source", r.source.clone()),
        ("updated_at", r.updated_at.clone()),
        ("catalog_version", version.to_string()),
    ];
    macro_rules! push_opt {
        ($key:expr, $field:expr) => {
            if let Some(ref v) = $field {
                fields.push(($key, v.to_string()));
            }
        };
    }
    push_opt!("imo", r.imo);
    push_opt!("name", r.name);
    push_opt!("normalized_name", r.normalized_name);
    push_opt!("callsign", r.callsign);
    push_opt!("flag", r.flag);
    push_opt!("flag_name", r.flag_name);
    push_opt!("type_code", r.type_code);
    push_opt!("type_name", r.type_name);
    push_opt!("subtype", r.subtype);
    push_opt!("length_m", r.length_m);
    push_opt!("width_m", r.width_m);
    push_opt!("draft_m", r.draft_m);
    push_opt!("year_built", r.year_built);
    push_opt!("gross_tonnage", r.gross_tonnage);
    push_opt!("deadweight_t", r.deadweight_t);
    fields
}

/// Scan and delete all keys belonging to a catalog version.
async fn delete_version(
    conn: &mut bb8::PooledConnection<'_, bb8_redis::RedisConnectionManager>,
    version: &str,
) -> Result<()> {
    let pattern = format!("vessel_catalog:version:{}:*", version);
    let mut cursor: u64 = 0;
    loop {
        let (next_cursor, keys): (u64, Vec<String>) = redis::cmd("SCAN")
            .arg(cursor)
            .arg("MATCH")
            .arg(&pattern)
            .arg("COUNT")
            .arg(200u64)
            .query_async(&mut **conn)
            .await
            .context("SCAN for version keys")?;

        if !keys.is_empty() {
            let _: usize = conn.del(keys).await.context("DEL version keys")?;
        }

        cursor = next_cursor;
        if cursor == 0 {
            break;
        }
    }
    Ok(())
}
