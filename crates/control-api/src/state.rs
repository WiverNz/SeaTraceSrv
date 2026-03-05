use crate::weather::WeatherClient;
use delivery::Broadcaster;
use std::sync::Arc;

#[derive(Clone)]
pub struct AppState {
    pub broadcaster: Arc<dyn Broadcaster>,
    pub weather_client: WeatherClient,
}

impl AppState {
    pub fn new(broadcaster: Arc<dyn Broadcaster>) -> Self {
        Self {
            broadcaster,
            weather_client: WeatherClient::new(),
        }
    }
}
