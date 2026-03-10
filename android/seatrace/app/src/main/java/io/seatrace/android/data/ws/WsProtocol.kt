package io.seatrace.android.data.ws

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// ── Client → Server ───────────────────────────────────────────────────────────

/**
 * Subscribe to a set of H3 cells (resolution 7, same as server indexing).
 * Sending a new message replaces the previous subscription entirely.
 */
@Serializable
data class SubscribeMessage(
    @SerialName("h3_cells") val h3Cells: List<Long>,
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
