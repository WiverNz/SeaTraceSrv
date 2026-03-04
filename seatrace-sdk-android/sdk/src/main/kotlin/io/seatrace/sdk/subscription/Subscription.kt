package io.seatrace.sdk.subscription

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Geographic bounding box for spatial subscriptions.
 */
@Serializable
data class BBox(
    /** Western longitude boundary */
    val west: Double,
    /** Southern latitude boundary */
    val south: Double,
    /** Eastern longitude boundary */
    val east: Double,
    /** Northern latitude boundary */
    val north: Double
) {
    init {
        require(west in -180.0..180.0) { "West longitude must be between -180 and 180" }
        require(east in -180.0..180.0) { "East longitude must be between -180 and 180" }
        require(south in -90.0..90.0) { "South latitude must be between -90 and 90" }
        require(north in -90.0..90.0) { "North latitude must be between -90 and 90" }
        require(south <= north) { "South latitude must be <= north latitude" }
    }

    companion object {
        /** Worldwide bounding box */
        val World = BBox(west = -180.0, south = -90.0, east = 180.0, north = 90.0)
    }
}

/**
 * Represents an active subscription.
 */
data class SubscriptionHandle(
    /** Unique subscription identifier */
    val id: String = UUID.randomUUID().toString(),
    /** Subscription type */
    val type: SubscriptionType,
    /** Subscription parameters */
    val params: SubscriptionParams,
    /** Whether subscription is currently active */
    var isActive: Boolean = true
) {
    /**
     * Cancel this subscription.
     */
    internal var onCancel: (() -> Unit)? = null

    fun cancel() {
        isActive = false
        onCancel?.invoke()
    }
}

/**
 * Types of subscriptions available.
 */
enum class SubscriptionType {
    VESSELS,
    EVENTS,
    WEATHER,
    ANIMALS,
    ALL
}

/**
 * Parameters for a subscription.
 */
sealed class SubscriptionParams {
    /** Subscribe to vessel positions */
    data class Vessels(
        val bbox: BBox? = null,
        val h3Cells: List<Long>? = null,
        val minConfidence: Float = 0.0f,
        val mmsiFilter: List<Long>? = null
    ) : SubscriptionParams()

    /** Subscribe to events */
    data class Events(
        val bbox: BBox? = null,
        val h3Cells: List<Long>? = null,
        val categories: List<String>? = null,
        val minConfidence: Float = 0.0f
    ) : SubscriptionParams()

    /** Subscribe to weather alerts */
    data class Weather(
        val bbox: BBox? = null,
        val h3Cells: List<Long>? = null,
        val severities: List<String>? = null
    ) : SubscriptionParams()

    /** Subscribe to animal sightings */
    data class Animals(
        val bbox: BBox? = null,
        val h3Cells: List<Long>? = null,
        val species: List<String>? = null,
        val minConfidence: Float = 0.0f
    ) : SubscriptionParams()

    /** Subscribe to all events (wildcard) */
    data class All(
        val h3Cells: List<Long> = emptyList()
    ) : SubscriptionParams()
}

/**
 * Message sent to server to create subscription.
 */
@Serializable
internal data class SubscriptionMessage(
    val h3_cells: List<Long> = emptyList()
)
