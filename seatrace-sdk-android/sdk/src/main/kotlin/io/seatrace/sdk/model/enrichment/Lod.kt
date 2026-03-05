package io.seatrace.sdk.model.enrichment

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Detail level flags a client can request in a subscription.
 *
 * Multiple values can be combined. The server enriches each event with the
 * requested data and omits fields that were not requested.
 *
 * Adding a new enrichment source in the future requires only:
 *  1. A new enum variant here (matching the server-side snake_case value)
 *  2. A corresponding data class in the `enrichment` package
 *  3. An optional field on [io.seatrace.sdk.model.Event]
 *
 * Usage:
 * ```kotlin
 * client.subscribeVessels(lod = listOf(Lod.WEATHER_CURRENT))
 * client.subscribeVessels(lod = listOf(Lod.WEATHER_CURRENT, Lod.WEATHER_HOURLY))
 * ```
 */
@Serializable
enum class Lod {
    /** Vessel position data only (always included by default). */
    @SerialName("vessels")
    VESSELS,

    /**
     * Add current weather conditions (temperature, wind speed, humidity)
     * at the vessel position, fetched from Open-Meteo.
     */
    @SerialName("weather_current")
    WEATHER_CURRENT,

    /**
     * Add hourly 24-hour weather forecast at the vessel position.
     * Implies [WEATHER_CURRENT].
     */
    @SerialName("weather_hourly")
    WEATHER_HOURLY,

    // ── Future enrichments ─────────────────────────────────────────────────
    // Uncomment and add server-side support when ready:

    // /** Real-time data from nearby buoys and channel sensors. */
    // @SerialName("water_conditions")
    // WATER_CONDITIONS,

    // /** Bathymetric depth at the vessel position. */
    // @SerialName("depth")
    // DEPTH,

    // /** Surface water current vector at the vessel position. */
    // @SerialName("water_currents")
    // WATER_CURRENTS,
}
