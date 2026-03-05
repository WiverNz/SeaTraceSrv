package io.seatrace.sdk.model.enrichment

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Current weather conditions at an event position.
 *
 * Populated when the client subscribes with [Lod.WEATHER_CURRENT] or
 * [Lod.WEATHER_HOURLY]. Data sourced from Open-Meteo, cached per H3 cell
 * for 15 minutes on the server.
 */
@Serializable
data class CurrentWeather(
    /** ISO 8601 observation timestamp. */
    val time: String,
    /** Air temperature at 2 m height (°C). */
    @SerialName("temperature_2m")
    val temperature2m: Float,
    /** Wind speed at 10 m height (km/h). */
    @SerialName("wind_speed_10m")
    val windSpeed10m: Float,
    /** Relative humidity at 2 m height (%). */
    @SerialName("relative_humidity_2m")
    val relativeHumidity2m: Float,
) {
    override fun toString(): String =
        "%.1f°C  %.1f km/h  %.0f%%rh".format(temperature2m, windSpeed10m, relativeHumidity2m)
}

/**
 * Hourly weather forecast for the next 24 hours.
 *
 * Populated when the client subscribes with [Lod.WEATHER_HOURLY].
 * Each list has 24 entries — one per hour starting at midnight local time.
 */
@Serializable
data class HourlyWeather(
    /** One ISO 8601 timestamp per hour (24 entries). */
    val time: List<String>,
    /** Air temperature at 2 m per hour (°C). */
    @SerialName("temperature_2m")
    val temperature2m: List<Float>,
    /** Wind speed at 10 m per hour (km/h). */
    @SerialName("wind_speed_10m")
    val windSpeed10m: List<Float>,
    /** Relative humidity at 2 m per hour (%). */
    @SerialName("relative_humidity_2m")
    val relativeHumidity2m: List<Float>,
) {
    /** Return a summary for a specific hour index. */
    fun atHour(index: Int): String =
        "${time[index]}  %.1f°C  %.1f km/h".format(temperature2m[index], windSpeed10m[index])
}

/**
 * Weather enrichment attached to an event when a weather LOD is active.
 *
 * Both fields are optional — presence depends on which LODs were requested:
 * - [current] is set for [Lod.WEATHER_CURRENT] and [Lod.WEATHER_HOURLY]
 * - [hourly]  is set for [Lod.WEATHER_HOURLY] only
 *
 * To add a new enrichment type (e.g. water conditions):
 *  1. Add a `WaterConditions` data class in this package
 *  2. Add `val waterConditions: WaterConditions? = null` to [io.seatrace.sdk.model.Event]
 *  3. Add the corresponding [Lod] variant
 */
@Serializable
data class WeatherEnrichment(
    val current: CurrentWeather? = null,
    val hourly: HourlyWeather? = null,
)
