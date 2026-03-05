package io.seatrace.sdk.model

import io.seatrace.sdk.model.enrichment.WeatherEnrichment
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Vessel position update from AIS data.
 *
 * This model mirrors the generated EventPayloadVesselPosition but provides
 * a cleaner API for SDK consumers.
 */
@Serializable
data class VesselPosition(
    /** Vessel type discriminator */
    val type: String = "VesselPosition",
    /** Maritime Mobile Service Identity - unique vessel identifier */
    val mmsi: Long,
    /** Latitude in decimal degrees */
    val lat: Double,
    /** Longitude in decimal degrees */
    val lon: Double,
    /** Speed Over Ground in knots (optional) */
    val sog: Float? = null,
    /** Course Over Ground in degrees (optional) */
    val cog: Float? = null
) {
    override fun toString(): String = "Vessel($mmsi @ $lat, $lon)"

    companion object {
        /**
         * Create from generated model if available.
         */
        fun fromGenerated(generated: Any): VesselPosition? {
            // Bridge to generated model when OpenAPI generator runs
            return null
        }
    }
}

/**
 * Weather alert event.
 */
@Serializable
data class WeatherAlert(
    val type: String = "WeatherAlert",
    /** Type of weather event (storm, fog, etc.) */
    val kind: String,
    /** Severity level */
    val severity: String,
    /** Polygon coordinates defining affected area */
    val polygon: List<List<Double>>
)

/**
 * Sea phenomenon observation.
 */
@Serializable
data class SeaPhenomenon(
    val type: String = "SeaPhenomenon",
    /** Type of phenomenon */
    val kind: String,
    /** Latitude */
    val lat: Double,
    /** Longitude */
    val lon: Double,
    /** Supporting evidence or description */
    val evidence: String? = null
)

/**
 * Maritime incident report.
 */
@Serializable
data class Incident(
    val type: String = "Incident",
    /** Type of incident */
    val kind: String,
    /** Latitude */
    val lat: Double,
    /** Longitude */
    val lon: Double,
    /** MMSI of involved vessel (if applicable) */
    @SerialName("vessel_mmsi")
    val vesselMmsi: Long? = null
)

/**
 * Animal sighting (marine wildlife tracking).
 */
@Serializable
data class AnimalSighting(
    val type: String = "AnimalSighting",
    /** Species identifier */
    val species: String,
    /** Latitude */
    val lat: Double,
    /** Longitude */
    val lon: Double,
    /** Number of individuals sighted */
    val count: Int = 1,
    /** Confidence score 0.0-1.0 */
    val confidence: Float = 1.0f
)

/**
 * Raw event payload for flexible deserialization.
 * Handles all event types from the server.
 */
@Serializable
data class EventPayloadRaw(
    val type: String,
    val mmsi: Long? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val sog: Float? = null,
    val cog: Float? = null,
    val kind: String? = null,
    val severity: String? = null,
    val polygon: List<List<Double>>? = null,
    val evidence: String? = null,
    @SerialName("vessel_mmsi")
    val vesselMmsi: Long? = null,
    val species: String? = null,
    val count: Int? = null,
    val confidence: Float? = null
) {
    /**
     * Convert to VesselPosition if payload type matches.
     */
    fun toVesselPosition(): VesselPosition? {
        if (type != "VesselPosition") return null
        return VesselPosition(
            mmsi = mmsi ?: return null,
            lat = lat ?: return null,
            lon = lon ?: return null,
            sog = sog,
            cog = cog
        )
    }

    /**
     * Convert to WeatherAlert if payload type matches.
     */
    fun toWeatherAlert(): WeatherAlert? {
        if (type != "WeatherAlert") return null
        return WeatherAlert(
            kind = kind ?: return null,
            severity = severity ?: return null,
            polygon = polygon ?: return null
        )
    }

    /**
     * Convert to SeaPhenomenon if payload type matches.
     */
    fun toSeaPhenomenon(): SeaPhenomenon? {
        if (type != "SeaPhenomenon") return null
        return SeaPhenomenon(
            kind = kind ?: return null,
            lat = lat ?: return null,
            lon = lon ?: return null,
            evidence = evidence
        )
    }

    /**
     * Convert to Incident if payload type matches.
     */
    fun toIncident(): Incident? {
        if (type != "Incident") return null
        return Incident(
            kind = kind ?: return null,
            lat = lat ?: return null,
            lon = lon ?: return null,
            vesselMmsi = vesselMmsi
        )
    }

    /**
     * Convert to AnimalSighting if payload type matches.
     */
    fun toAnimalSighting(): AnimalSighting? {
        if (type != "AnimalSighting") return null
        return AnimalSighting(
            species = species ?: return null,
            lat = lat ?: return null,
            lon = lon ?: return null,
            count = count ?: 1,
            confidence = confidence ?: 1.0f
        )
    }
}

/**
 * Main event envelope containing metadata and typed payload.
 *
 * This is the primary event structure received from the server.
 * Use the payload conversion methods to get typed data.
 */
@Serializable
data class Event(
    /** Unique event identifier */
    @SerialName("event_id")
    val eventId: String,
    /** H3 spatial index */
    @SerialName("h3_index")
    val h3Index: Long,
    /** Unix timestamp in milliseconds */
    val timestamp: Long,
    /** Data source identifier */
    val source: String,
    /** Confidence score 0.0-1.0 */
    val confidence: Float,
    /** Event payload - use payloadAs<T>() to access typed data */
    val payload: EventPayloadRaw,
    /**
     * Weather enrichment — present when the client subscribed with
     * [io.seatrace.sdk.model.enrichment.Lod.WEATHER_CURRENT] or
     * [io.seatrace.sdk.model.enrichment.Lod.WEATHER_HOURLY].
     */
    val weather: WeatherEnrichment? = null,
) {
    /** Convert timestamp to Java Instant */
    @get:android.annotation.SuppressLint("NewApi")
    val instant: java.time.Instant
        get() = java.time.Instant.ofEpochMilli(timestamp)

    /** Convert timestamp to formatted string */
    @get:android.annotation.SuppressLint("NewApi")
    val formattedTime: String
        get() = java.time.format.DateTimeFormatter.ISO_INSTANT.format(instant)
}

/**
 * Typed update wrapper for vessel positions.
 *
 * [weather] is a convenience accessor — it is non-null when the client
 * subscribed with a weather LOD. New enrichment types follow the same pattern:
 * add a property that delegates to [event].
 */
data class VesselUpdate(
    val event: Event,
    val position: VesselPosition,
) {
    /** Weather enrichment if a weather LOD was requested, null otherwise. */
    val weather: WeatherEnrichment? get() = event.weather

    override fun toString(): String = "VesselUpdate(${position.mmsi} @ ${event.formattedTime})"
}

/**
 * Typed update wrapper for weather alerts.
 */
data class WeatherUpdate(
    val event: Event,
    val alert: WeatherAlert
)

/**
 * Typed update wrapper for events/phenomena.
 */
data class EventUpdate(
    val event: Event
)

/**
 * Typed update wrapper for animal sightings.
 */
data class AnimalUpdate(
    val event: Event,
    val sighting: AnimalSighting
)

/**
 * Health response from the server.
 */
@Serializable
data class HealthResponse(
    val status: String,
    val components: Map<String, String> = emptyMap()
)

/**
 * Source status from the server.
 */
@Serializable
data class SourceStatus(
    val id: String,
    val health: String,
    @SerialName("quality_score")
    val qualityScore: Float,
    val active: Boolean
)
