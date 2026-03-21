package io.seatrace.android.data.model

/**
 * Runtime model for a vessel tracked via the AIS pipeline.
 *
 * @param mmsi       Maritime Mobile Service Identity (9 digits).
 * @param lat        Latitude in decimal degrees (WGS-84).
 * @param lon        Longitude in decimal degrees (WGS-84).
 * @param sog        Speed over ground in knots, if reported.
 * @param cog        Course over ground in degrees [0–360), if reported.
 * @param lastSeen   Unix timestamp in milliseconds of the most recent position update.
 */
data class Ship(
    val mmsi: Long,
    val lat: Double,
    val lon: Double,
    val sog: Double?,
    val cog: Double?,
    val lastSeen: Long,
    /** Vessel name from the server catalog, or null if unknown. */
    val name: String? = null,
)
