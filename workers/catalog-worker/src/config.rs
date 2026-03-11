use std::time::Duration;

pub struct Config {
    pub redis_url: String,
    /// How long to wait between full catalog rebuilds (Deployment mode only).
    pub refresh_interval: Duration,
    /// Root directory where map/chart data is stored.
    pub data_dir: String,
    /// How many catalog versions to keep in Redis (oldest are pruned).
    pub versions_to_keep: usize,
    /// If true, run exactly one build cycle then exit with code 0 (success) or 1 (failure).
    /// Intended for CronJob deployments where Kubernetes handles scheduling.
    pub run_once: bool,
}

impl Config {
    pub fn from_env() -> Self {
        let refresh_secs: u64 = std::env::var("CATALOG_REFRESH_INTERVAL_SECS")
            .ok()
            .and_then(|v| v.parse().ok())
            .unwrap_or(3600);

        let versions_to_keep: usize = std::env::var("CATALOG_VERSIONS_TO_KEEP")
            .ok()
            .and_then(|v| v.parse().ok())
            .unwrap_or(3);

        let run_once = std::env::var("RUN_ONCE")
            .map(|v| matches!(v.to_lowercase().as_str(), "true" | "1" | "yes"))
            .unwrap_or(false);

        Self {
            redis_url: std::env::var("REDIS_URL")
                .unwrap_or_else(|_| "redis://127.0.0.1:6379".to_string()),
            refresh_interval: Duration::from_secs(refresh_secs),
            data_dir: std::env::var("DATA_DIR").unwrap_or_else(|_| "/data".to_string()),
            versions_to_keep,
            run_once,
        }
    }
}
