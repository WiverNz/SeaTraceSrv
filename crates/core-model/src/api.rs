use serde::{Deserialize, Serialize};
use crate::{Event, HealthStatus};
use std::collections::HashMap;

// ============================================
// Control API (HTTP/REST) Contracts
// ============================================

/// GET /api/v1/health
#[derive(Debug, Serialize, Deserialize)]
pub struct HealthResponse {
    pub status: String,
    pub components: HashMap<String, String>,
}

/// GET /api/v1/sources
#[derive(Debug, Serialize, Deserialize)]
pub struct SourceStatus {
    pub id: String,
    pub health: HealthStatus,
    pub quality_score: f32,
    pub active: bool,
}

/// POST /api/v1/snapshot 
/// Запросить состояние без подписки на стрим (pull модель)
#[derive(Debug, Serialize, Deserialize)]
pub struct SnapshotRequest {
    pub h3_cells: Vec<u64>,
    pub categories: Option<Vec<String>>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct SnapshotResponse {
    pub events: Vec<Event>,
}

// ============================================
// Realtime API (WebSocket/WebTransport) Contracts
// ============================================

/// Сообщения от Клиента к Серверу
#[derive(Debug, Serialize, Deserialize)]
#[serde(tag = "type", content = "payload")]
pub enum ClientMessage {
    SubscribeArea {
        request_id: String,
        h3_cells: Vec<u64>,
        filters: Option<SubscriptionFilters>,
    },
    UnsubscribeArea {
        request_id: String,
        h3_cells: Vec<u64>,
    },
}

#[derive(Debug, Serialize, Deserialize)]
pub struct SubscriptionFilters {
    pub categories: Option<Vec<String>>, // например ["VesselPosition", "WeatherAlert"]
    pub min_confidence: Option<f32>,
}

/// Сообщения от Сервера к Клиенту
#[derive(Debug, Serialize, Deserialize)]
#[serde(tag = "type", content = "payload")]
pub enum ServerMessage {
    /// Полный слепок состояния для запрошеннных ячеек (ответ на SubscribeArea)
    AreaSnapshot {
        reply_to: String,
        events: Vec<Event>,
    },
    /// Инкрементальное обновление (новые события или изменения существующих)
    AreaUpdate {
        events: Vec<Event>,
    },
    /// Системные ошибки или уведомления
    SystemError {
        code: String,
        message: String,
    },
}
