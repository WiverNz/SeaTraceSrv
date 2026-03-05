use serde::Deserialize;

/// Detail levels a client can request in the WebSocket subscription message.
/// Multiple values can be combined in the `lod` array.
///
/// Example subscription message:
/// ```json
/// { "h3_cells": [], "lod": ["weather_current"] }
/// { "h3_cells": [], "lod": ["weather_current", "weather_hourly"] }
/// ```
#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Lod {
    /// Vessel position events only (default when `lod` is empty or omitted).
    Vessels,
    /// Enrich each event with current weather conditions at the vessel position
    /// (temperature, wind speed, relative humidity) from Open-Meteo.
    WeatherCurrent,
    /// Enrich each event with the hourly 24-hour forecast in addition to
    /// current conditions. Implies `WeatherCurrent`.
    WeatherHourly,
}
