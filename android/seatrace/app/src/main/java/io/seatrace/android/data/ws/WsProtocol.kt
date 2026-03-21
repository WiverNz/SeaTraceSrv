package io.seatrace.android.data.ws

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// ── Client → Server ───────────────────────────────────────────────────────────

/**
 * Geographic bounding box sent to the server to establish a viewport subscription.
 * Matches the server-side `Viewport` struct in the delivery crate.
 */
@Serializable
data class Viewport(
    val north: Double,
    val south: Double,
    val east: Double,
    val west: Double,
)

/**
 * Subscribe to vessel events within a visible map viewport.
 * Sending a new message replaces the previous subscription entirely.
 */
@Serializable
data class ViewportMessage(
    val viewport: Viewport,
)

// ── Server → Client ───────────────────────────────────────────────────────────

/**
 * Raw server message envelope.
 * - Control messages have a `type` field ("SubscribeAck", "Error").
 * - Vessel events have `event_id` / `payload` and no top-level `type`.
 */
@Serializable
data class ServerEnvelope(
    /** Present on control messages (SubscribeAck, Error). */
    val type: String? = null,
    /** Present on vessel-event messages. */
    @SerialName("event_id") val eventId: String? = null,
    @SerialName("h3_index") val h3Index: Long? = null,
    val timestamp: Long? = null,
    val source: String? = null,
    val confidence: Double? = null,
    val payload: JsonElement? = null,
    /** Vessel name resolved from the server-side catalog, if available. */
    @SerialName("vessel_name") val vesselName: String? = null,
)

/**
 * Inner payload for a VesselPosition event.
 * The `type` field discriminates payload variants (only VesselPosition exists today).
 */
@Serializable
data class VesselPositionPayload(
    val type: String,
    val mmsi: Long,
    val lat: Double,
    val lon: Double,
    val sog: Double? = null,
    val cog: Double? = null,
)
