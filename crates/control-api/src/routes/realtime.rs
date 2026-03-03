use axum::{
    extract::{ws::{Message, WebSocket, WebSocketUpgrade}, State},
    response::IntoResponse,
};
use futures_util::stream::StreamExt;
use serde::Deserialize;

use crate::state::AppState;

#[derive(Debug, Deserialize)]
pub struct SubscribeMessage {
    pub h3_cells: Vec<u64>,
}

pub async fn realtime_handler(
    ws: WebSocketUpgrade,
    State(state): State<AppState>,
) -> impl IntoResponse {
    ws.on_upgrade(|socket| handle_socket(socket, state))
}

async fn handle_socket(mut socket: WebSocket, state: AppState) {
    // 1. Ждем первого сообщения от клиента с H3-подпиской
    let first_msg = match socket.next().await {
        Some(Ok(msg)) => msg,
        _ => return, // Client disconnected or error
    };

    let text = match first_msg {
        Message::Text(t) => t,
        _ => {
            let _ = socket.send(Message::Text("Expected text message".into())).await;
            return;
        }
    };

    let sub: SubscribeMessage = match serde_json::from_str(&text) {
        Ok(s) => s,
        Err(e) => {
            let _ = socket.send(Message::Text(format!("Invalid subscription JSON: {}", e).into())).await;
            return;
        }
    };

    // Генерим уникальный ID для этого WebSocket
    let client_id = uuid::Uuid::new_v4().to_string();

    // 2. Оформляем подписку в Broadcaster
    let mut rx = state.broadcaster.subscribe(&client_id, sub.h3_cells).await;

    // 3. Открываем цикл пересылки событий клиенту
    loop {
        tokio::select! {
            result = rx.recv() => {
                match result {
                    Ok(event) => {
                        if let Ok(json) = serde_json::to_string(&event) {
                            if socket.send(Message::Text(json.into())).await.is_err() {
                                // Client disconnected
                                break;
                            }
                        }
                    }
                    Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => {
                        // Клиент не успевает вычитывать, пропускаем события
                        continue;
                    }
                    Err(tokio::sync::broadcast::error::RecvError::Closed) => {
                        // Бродкастер завершил работу
                        break;
                    }
                }
            }
            client_msg = socket.next() => {
                // Если клиент прислал сообщение: пинг/понг или закрытие
                match client_msg {
                    Some(Ok(Message::Close(_))) => break,
                    Some(Ok(_)) => {}, // ignore other msgs after subscribe in MVP
                    _ => break, // Error or channel closed
                }
            }
        }
    }
}
