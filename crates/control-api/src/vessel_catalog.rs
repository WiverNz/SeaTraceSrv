use crate::state::AppState;
use redis::AsyncCommands;
use serde_json::Value;
use std::time::Duration;
use tracing::{debug, error, info, warn};

/// Background task that polls Redis for the current active catalog version
/// and updates the cached version in `AppState`.
pub async fn start_catalog_poller(state: AppState) {
    let mut interval = tokio::time::interval(Duration::from_secs(5));
    
    loop {
        interval.tick().await;

        let mut conn = match state.redis_pool.get().await {
            Ok(conn) => conn,
            Err(e) => {
                error!("Redis catalog poller: failed to get connection: {}", e);
                continue;
            }
        };

        let result: redis::RedisResult<Option<String>> = conn.get("vessel_catalog:active_version").await;
        
        match result {
            Ok(Some(new_version)) => {
                let mut cached = state.active_catalog_version.write().await;
                if cached.as_deref() != Some(new_version.as_str()) {
                    info!("Active vessel catalog version switched to: {}", new_version);
                    *cached = Some(new_version);
                    // Invalidate the name cache so the new version's names are loaded fresh.
                    state.vessel_name_cache.write().await.clear();
                }
            }
            Ok(None) => {
                debug!("vessel_catalog:active_version is not set yet");
            }
            Err(e) => {
                error!("Redis catalog poller: failed to get active_version: {}", e);
            }
        }
    }
}

/// Helper to lookup a vessel by MMSI using the currently cached active version in AppState.
pub async fn lookup_mmsi(state: &AppState, mmsi: i64) -> Option<Value> {
    let version_opt = {
        let guard = state.active_catalog_version.read().await;
        guard.clone()
    };

    let version = match version_opt {
        Some(v) => v,
        None => return None, // no active version set
    };

    let mut conn = match state.redis_pool.get().await {
        Ok(conn) => conn,
        Err(e) => {
            warn!("Failed to get Redis connection for lookup: {}", e);
            return None;
        }
    };

    let key = format!("vessel_catalog:version:{}:mmsi:{}", version, mmsi);
    let result: redis::RedisResult<std::collections::HashMap<String, String>> = conn.hgetall(&key).await;

    match result {
        Ok(map) if map.is_empty() => {
            // Redis returns an empty map if the hash doesn't exist
            None
        }
        Ok(map) => {
            // Convert HashMap<String, String> into a JSON Value object.
            let mut json_map = serde_json::Map::new();
            for (k, v) in map {
                // Try to parse as numbers where it makes sense, or keep as string
                if let Ok(num) = v.parse::<i64>() {
                    json_map.insert(k, Value::Number(num.into()));
                } else if let Ok(num) = v.parse::<f64>() {
                    if let Some(n) = serde_json::Number::from_f64(num) {
                        json_map.insert(k, Value::Number(n));
                    } else {
                        json_map.insert(k, Value::String(v));
                    }
                } else {
                    json_map.insert(k, Value::String(v));
                }
            }
            Some(Value::Object(json_map))
        }
        Err(e) => {
            warn!("Redis HGETALL failed for key {}: {}", key, e);
            None
        }
    }
}

/// Look up only the vessel name for a given MMSI, using an in-memory cache.
///
/// Returns `Some(name)` when the catalog has a non-empty name for this MMSI,
/// `None` otherwise. Results are cached until the active catalog version changes.
pub async fn lookup_vessel_name(state: &AppState, mmsi: i64) -> Option<String> {
    // Fast path: check in-memory cache.
    {
        let cache = state.vessel_name_cache.read().await;
        if let Some(cached) = cache.get(&mmsi) {
            return cached.clone();
        }
    }

    // Cache miss: resolve active version.
    let version = {
        let guard = state.active_catalog_version.read().await;
        guard.clone()
    }?;

    // Single-field HGET — much cheaper than HGETALL.
    let mut conn = match state.redis_pool.get().await {
        Ok(c) => c,
        Err(e) => {
            warn!("vessel_name lookup: failed to get Redis connection: {}", e);
            return None;
        }
    };
    let key = format!("vessel_catalog:version:{}:mmsi:{}", version, mmsi);
    let result: redis::RedisResult<Option<String>> = conn.hget(&key, "name").await;

    let name = match result {
        Ok(Some(n)) if !n.is_empty() => Some(n),
        Ok(_) => None,
        Err(e) => {
            warn!("Redis HGET name failed for {}: {}", key, e);
            None
        }
    };

    // Store in cache (including None, to avoid repeated misses for unknown MMSIs).
    state.vessel_name_cache.write().await.insert(mmsi, name.clone());
    name
}
