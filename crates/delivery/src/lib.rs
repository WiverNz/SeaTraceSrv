use anyhow::Result;
use async_trait::async_trait;
use core_model::Event;
use std::collections::{HashMap, HashSet};
use tokio::sync::{broadcast, RwLock};

#[async_trait]
pub trait Broadcaster: Send + Sync {
    /// Подключить клиента к ячейкам. Возвращает канал (stream) для отправки ему событий.
    async fn subscribe(&self, client_id: &str, h3_cells: Vec<u64>) -> broadcast::Receiver<Event>;

    /// Отправить событие в шину (дальше оно дойдет до нужных подписчиков)
    async fn broadcast(&self, event: Event) -> Result<()>;
}

/// Простейший in-memory бродкастер для локальной разработки и тестов
#[derive(Debug)]
pub struct InMemoryBroadcaster {
    // Канал на каждого клиента
    clients: RwLock<HashMap<String, broadcast::Sender<Event>>>,
    // Подписки: h3_index -> список client_id
    subscriptions: RwLock<HashMap<u64, HashSet<String>>>,
    // Wildcard subscribers (receive ALL events) - useful for monitoring/testing
    wildcard_subscribers: RwLock<HashSet<String>>,
}

impl InMemoryBroadcaster {
    pub fn new() -> Self {
        Self {
            clients: RwLock::new(HashMap::new()),
            subscriptions: RwLock::new(HashMap::new()),
            wildcard_subscribers: RwLock::new(HashSet::new()),
        }
    }
}

impl Default for InMemoryBroadcaster {
    fn default() -> Self {
        Self::new()
    }
}

#[async_trait]
impl Broadcaster for InMemoryBroadcaster {
    async fn subscribe(&self, client_id: &str, h3_cells: Vec<u64>) -> broadcast::Receiver<Event> {
        let mut clients = self.clients.write().await;
        // Пересоздаем канал для клиента (или используем существующий).
        // В MVP создаем новый канал на 1024 сообщения.
        let (tx, rx) = broadcast::channel(1024);
        clients.insert(client_id.to_string(), tx.clone());

        // Empty cell list = wildcard subscription (receive ALL events)
        if h3_cells.is_empty() {
            let mut wildcards = self.wildcard_subscribers.write().await;
            wildcards.insert(client_id.to_string());
        } else {
            let mut subs = self.subscriptions.write().await;
            for cell in h3_cells {
                subs.entry(cell).or_default().insert(client_id.to_string());
            }
        }

        rx
    }

    async fn broadcast(&self, event: Event) -> Result<()> {
        let subs = self.subscriptions.read().await;
        let clients = self.clients.read().await;
        let wildcards = self.wildcard_subscribers.read().await;

        // Send to clients subscribed to specific H3 cell
        if let Some(subscribers) = subs.get(&event.h3_index) {
            for client_id in subscribers {
                if let Some(tx) = clients.get(client_id) {
                    // Игнорируем ошибку (канал мог закрыться, если клиент отключен,
                    // нужно очищать стейт в будущем)
                    let _ = tx.send(event.clone());
                }
            }
        }

        // Send to wildcard subscribers (they receive ALL events)
        for client_id in wildcards.iter() {
            if let Some(tx) = clients.get(client_id) {
                let _ = tx.send(event.clone());
            }
        }

        Ok(())
    }
}
