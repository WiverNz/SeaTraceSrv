use delivery::Broadcaster;
use std::sync::Arc;

#[derive(Clone)]
pub struct AppState {
    pub broadcaster: Arc<dyn Broadcaster>,
}

impl AppState {
    pub fn new(broadcaster: Arc<dyn Broadcaster>) -> Self {
        Self { broadcaster }
    }
}
