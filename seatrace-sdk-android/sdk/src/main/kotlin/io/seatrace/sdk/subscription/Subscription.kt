package io.seatrace.sdk.subscription

import io.seatrace.sdk.model.enrichment.Lod
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Geographic bounding box for spatial subscriptions.
 */
@Serializable
data class BBox(
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
) {
    init {
        require(west in -180.0..180.0) { "West longitude must be between -180 and 180" }
        require(east in -180.0..180.0) { "East longitude must be between -180 and 180" }
        require(south in -90.0..90.0) { "South latitude must be between -90 and 90" }
        require(north in -90.0..90.0) { "North latitude must be between -90 and 90" }
        require(south <= north) { "South latitude must be <= north latitude" }
    }

    companion object {
        val World = BBox(west = -180.0, south = -90.0, east = 180.0, north = 90.0)
    }
}

/**
 * Handle returned from every `subscribe*` call.
 * Call [cancel] to remove the subscription.
 */
data class SubscriptionHandle(
    val id: String = UUID.randomUUID().toString(),
    val type: SubscriptionType,
    val params: SubscriptionParams,
    var isActive: Boolean = true,
) {
    internal var onCancel: (() -> Unit)? = null

    fun cancel() {
        isActive = false
        onCancel?.invoke()
    }
}

/** High-level subscription categories. */
enum class SubscriptionType {
    VESSELS,
    EVENTS,
    WEATHER,
    ANIMALS,
    ALL,
}

/**
 * Parameters carried by each subscription.
 *
 * Every subclass exposes a [lod] list that is forwarded verbatim in the
 * WebSocket subscribe message. To request a new enrichment type, pass the
 * corresponding [Lod] variant — no other client code needs to change.
 */
sealed class SubscriptionParams {
    abstract val lod: List<Lod>

    /** Subscribe to vessel positions. */
    data class Vessels(
        val bbox: BBox? = null,
        val h3Cells: List<Long>? = null,
        val minConfidence: Float = 0.0f,
        val mmsiFilter: List<Long>? = null,
        override val lod: List<Lod> = listOf(Lod.VESSELS),
    ) : SubscriptionParams()

    /** Subscribe to general events (sea phenomena, incidents). */
    data class Events(
        val bbox: BBox? = null,
        val h3Cells: List<Long>? = null,
        val categories: List<String>? = null,
        val minConfidence: Float = 0.0f,
        override val lod: List<Lod> = listOf(Lod.VESSELS),
    ) : SubscriptionParams()

    /** Subscribe to weather alerts. */
    data class Weather(
        val bbox: BBox? = null,
        val h3Cells: List<Long>? = null,
        val severities: List<String>? = null,
        override val lod: List<Lod> = listOf(Lod.VESSELS),
    ) : SubscriptionParams()

    /** Subscribe to animal sightings. */
    data class Animals(
        val bbox: BBox? = null,
        val h3Cells: List<Long>? = null,
        val species: List<String>? = null,
        val minConfidence: Float = 0.0f,
        override val lod: List<Lod> = listOf(Lod.VESSELS),
    ) : SubscriptionParams()

    /** Wildcard subscription — receives all events. */
    data class All(
        val h3Cells: List<Long> = emptyList(),
        override val lod: List<Lod> = listOf(Lod.VESSELS),
    ) : SubscriptionParams()
}

/**
 * Wire message sent to the server to establish a subscription.
 *
 * Serialised to JSON:
 * ```json
 * { "h3_cells": [608431123508232191], "lod": ["weather_current"] }
 * ```
 */
@Serializable
internal data class SubscriptionMessage(
    @SerialName("h3_cells")
    val h3Cells: List<Long> = emptyList(),
    val lod: List<Lod> = emptyList(),
)
