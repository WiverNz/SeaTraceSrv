package io.seatrace.sdk

import io.seatrace.sdk.connection.ConnectionState
import io.seatrace.sdk.connection.WebSocketTransport
import io.seatrace.sdk.debug.*
import io.seatrace.sdk.error.SeaTraceError
import io.seatrace.sdk.model.*
import io.seatrace.sdk.model.enrichment.Lod
import io.seatrace.sdk.model.enrichment.WeatherEnrichment
import io.seatrace.sdk.subscription.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/**
 * Main entry point for SeaTrace SDK.
 *
 * Example usage:
 * ```
 * val client = SeaTraceClient(
 *     endpoint = "wss://api.seatrace.example/realtime",
 *     tokenProvider = { authToken }
 * )
 *
 * client.connect()
 *
 * // Subscribe to vessel positions
 * val handle = client.subscribeVessels(
 *     bbox = BBox(west = -10.0, south = 35.0, east = 30.0, north = 60.0)
 * )
 *
 * // Collect updates
 * client.vesselsFlow.collect { update ->
 *     println("Vessel ${update.position.mmsi} at ${update.position.lat}, ${update.position.lon}")
 * }
 * ```
 */
class SeaTraceClient private constructor(
    private val config: SeaTraceConfig
) {
    private val tag = "SeaTraceClient"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val transport = WebSocketTransport(config, scope)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    // Active subscriptions
    private val subscriptions = ConcurrentHashMap<String, SubscriptionHandle>()

    // Event flows
    private val _vesselsFlow = MutableSharedFlow<VesselUpdate>(extraBufferCapacity = config.maxQueueSize)
    private val _eventsFlow = MutableSharedFlow<EventUpdate>(extraBufferCapacity = config.maxQueueSize)
    private val _weatherFlow = MutableSharedFlow<WeatherUpdate>(extraBufferCapacity = config.maxQueueSize)
    private val _animalsFlow = MutableSharedFlow<AnimalUpdate>(extraBufferCapacity = config.maxQueueSize)
    private val _allEventsFlow = MutableSharedFlow<Event>(extraBufferCapacity = config.maxQueueSize)

    // Debug listeners
    private var rawMessageListener: RawMessageListener? = null
    private var parsedEventListener: ParsedEventListener? = null

    /**
     * Flow of vessel position updates.
     */
    val vesselsFlow: SharedFlow<VesselUpdate> = _vesselsFlow.asSharedFlow()

    /**
     * Flow of general event updates.
     */
    val eventsFlow: SharedFlow<EventUpdate> = _eventsFlow.asSharedFlow()

    /**
     * Flow of weather alert updates.
     */
    val weatherFlow: SharedFlow<WeatherUpdate> = _weatherFlow.asSharedFlow()

    /**
     * Flow of animal sighting updates.
     */
    val animalsFlow: SharedFlow<AnimalUpdate> = _animalsFlow.asSharedFlow()

    /**
     * Flow of all events (unfiltered).
     */
    val allEventsFlow: SharedFlow<Event> = _allEventsFlow.asSharedFlow()

    /**
     * Flow of errors from the SDK.
     */
    val errorsFlow: SharedFlow<SeaTraceError> = transport.errors

    /**
     * Current connection state.
     */
    val connectionState: StateFlow<ConnectionState> = transport.connectionState

    /**
     * Whether the client is currently connected.
     */
    val isConnected: StateFlow<Boolean> = connectionState.map { it.isConnected }
        .stateIn(scope, SharingStarted.Eagerly, false)

    init {
        // Set up message processing
        scope.launch {
            transport.messages.collect { message ->
                processMessage(message)
            }
        }

        // Resubscribe on reconnect
        scope.launch {
            connectionState.collect { state ->
                if (state is ConnectionState.Connected && subscriptions.isNotEmpty()) {
                    resubscribeAll()
                }
            }
        }
    }

    /**
     * Connect to the SeaTrace server.
     */
    suspend fun connect() {
        transport.connect()
    }

    /**
     * Disconnect from the server.
     */
    fun disconnect() {
        subscriptions.clear()
        transport.disconnect()
    }

    /**
     * Subscribe to vessel position updates.
     *
     * @param bbox Bounding box to filter positions (optional)
     * @param h3Cells H3 cells to subscribe to (optional, empty = all)
     * @param minConfidence Minimum confidence threshold (0.0–1.0)
     * @param mmsiFilter List of MMSIs to filter (optional)
     * @param lod Detail levels to request. Use [Lod.WEATHER_CURRENT] to receive
     *   current weather conditions attached to each event, [Lod.WEATHER_HOURLY]
     *   for a 24-hour hourly forecast. Access via [VesselUpdate.weather].
     * @return SubscriptionHandle for managing the subscription
     */
    fun subscribeVessels(
        bbox: BBox? = null,
        h3Cells: List<Long>? = null,
        minConfidence: Float = 0.0f,
        mmsiFilter: List<Long>? = null,
        lod: List<Lod> = listOf(Lod.VESSELS),
    ): SubscriptionHandle {
        val params = SubscriptionParams.Vessels(
            bbox = bbox,
            h3Cells = h3Cells,
            minConfidence = minConfidence,
            mmsiFilter = mmsiFilter,
            lod = lod,
        )
        return createSubscription(SubscriptionType.VESSELS, params)
    }

    /**
     * Subscribe to general events (sea phenomena, incidents).
     *
     * @param bbox Bounding box (optional)
     * @param h3Cells H3 cells (optional, empty = all)
     * @param categories Event categories to include (optional)
     * @param minConfidence Minimum confidence threshold
     * @param lod Detail levels to request (see [subscribeVessels])
     * @return SubscriptionHandle
     */
    fun subscribeEvents(
        bbox: BBox? = null,
        h3Cells: List<Long>? = null,
        categories: List<String>? = null,
        minConfidence: Float = 0.0f,
        lod: List<Lod> = listOf(Lod.VESSELS),
    ): SubscriptionHandle {
        val params = SubscriptionParams.Events(
            bbox = bbox,
            h3Cells = h3Cells,
            categories = categories,
            minConfidence = minConfidence,
            lod = lod,
        )
        return createSubscription(SubscriptionType.EVENTS, params)
    }

    /**
     * Subscribe to weather alerts.
     *
     * @param bbox Bounding box (optional)
     * @param h3Cells H3 cells (optional)
     * @param severities Severity levels to include (optional)
     * @param lod Detail levels to request (see [subscribeVessels])
     * @return SubscriptionHandle
     */
    fun subscribeWeather(
        bbox: BBox? = null,
        h3Cells: List<Long>? = null,
        severities: List<String>? = null,
        lod: List<Lod> = listOf(Lod.VESSELS),
    ): SubscriptionHandle {
        val params = SubscriptionParams.Weather(
            bbox = bbox,
            h3Cells = h3Cells,
            severities = severities,
            lod = lod,
        )
        return createSubscription(SubscriptionType.WEATHER, params)
    }

    /**
     * Subscribe to animal sightings.
     *
     * @param bbox Bounding box (optional)
     * @param h3Cells H3 cells (optional)
     * @param species Species to filter (optional)
     * @param minConfidence Minimum confidence threshold
     * @param lod Detail levels to request (see [subscribeVessels])
     * @return SubscriptionHandle
     */
    fun subscribeAnimals(
        bbox: BBox? = null,
        h3Cells: List<Long>? = null,
        species: List<String>? = null,
        minConfidence: Float = 0.0f,
        lod: List<Lod> = listOf(Lod.VESSELS),
    ): SubscriptionHandle {
        val params = SubscriptionParams.Animals(
            bbox = bbox,
            h3Cells = h3Cells,
            species = species,
            minConfidence = minConfidence,
            lod = lod,
        )
        return createSubscription(SubscriptionType.ANIMALS, params)
    }

    /**
     * Wildcard subscription — receives all events.
     *
     * @param h3Cells H3 cells (empty = truly all events)
     * @param lod Detail levels to request (see [subscribeVessels])
     * @return SubscriptionHandle
     */
    fun subscribeAll(
        h3Cells: List<Long> = emptyList(),
        lod: List<Lod> = listOf(Lod.VESSELS),
    ): SubscriptionHandle {
        val params = SubscriptionParams.All(h3Cells = h3Cells, lod = lod)
        return createSubscription(SubscriptionType.ALL, params)
    }

    /**
     * Unsubscribe from a specific subscription.
     */
    fun unsubscribe(handle: SubscriptionHandle) {
        handle.cancel()
        subscriptions.remove(handle.id)
        // TODO: Send unsubscribe message to server when protocol supports it
    }

    /**
     * Clear all subscriptions.
     */
    fun clearSubscriptions() {
        subscriptions.values.forEach { it.cancel() }
        subscriptions.clear()
    }

    /**
     * Set a listener for raw WebSocket messages (for debugging).
     */
    fun setRawMessageListener(listener: RawMessageListener?) {
        rawMessageListener = listener
        transport.rawMessageListener = listener
    }

    /**
     * Set a listener for parsed events (for debugging).
     */
    fun setParsedEventListener(listener: ParsedEventListener?) {
        parsedEventListener = listener
    }

    /**
     * Close the client and release resources.
     */
    fun close() {
        disconnect()
        scope.cancel()
    }

    private fun createSubscription(type: SubscriptionType, params: SubscriptionParams): SubscriptionHandle {
        val handle = SubscriptionHandle(type = type, params = params)
        handle.onCancel = { subscriptions.remove(handle.id) }
        subscriptions[handle.id] = handle

        // Send subscription to server
        sendSubscription(params)

        return handle
    }

    private fun sendSubscription(params: SubscriptionParams) {
        val h3Cells = when (params) {
            is SubscriptionParams.Vessels -> params.h3Cells ?: emptyList()
            is SubscriptionParams.Events -> params.h3Cells ?: emptyList()
            is SubscriptionParams.Weather -> params.h3Cells ?: emptyList()
            is SubscriptionParams.Animals -> params.h3Cells ?: emptyList()
            is SubscriptionParams.All -> params.h3Cells
        }

        val message = json.encodeToString(
            SubscriptionMessage.serializer(),
            SubscriptionMessage(h3Cells = h3Cells, lod = params.lod),
        )

        transport.send(message)
    }

    private fun resubscribeAll() {
        log(LogLevel.INFO, "Resubscribing to ${subscriptions.size} subscription(s)")
        subscriptions.values.filter { it.isActive }.forEach { handle ->
            sendSubscription(handle.params)
        }
    }

    private suspend fun processMessage(rawMessage: String) {
        try {
            val jsonElement = json.parseToJsonElement(rawMessage)
            if (jsonElement is kotlinx.serialization.json.JsonObject) {
                val type = jsonElement.jsonObject["type"]?.jsonPrimitive?.content
                if (type == "SubscribeAck" || type == "Error") {
                    if (type == "Error") {
                        val msg = jsonElement.jsonObject["message"]?.jsonPrimitive?.content
                        log(LogLevel.ERROR, "Server Error: $msg")
                    } else {
                        log(LogLevel.DEBUG, "Subscription acknowledged: ${jsonElement.jsonObject["active_cells"]?.jsonPrimitive?.content} cells active")
                    }
                    return
                }
            }

            val event = json.decodeFromJsonElement(Event.serializer(), jsonElement)

            parsedEventListener?.onEvent(event)

            // Emit to all events flow
            _allEventsFlow.emit(event)

            // Route to specific flows based on payload type
            when (event.payload.type) {
                "VesselPosition" -> {
                    event.payload.toVesselPosition()?.let { position ->
                        if (shouldEmitVessel(event, position)) {
                            _vesselsFlow.emit(VesselUpdate(event, position))
                        }
                    }
                }
                "WeatherAlert" -> {
                    event.payload.toWeatherAlert()?.let { alert ->
                        _weatherFlow.emit(WeatherUpdate(event, alert))
                    }
                }
                "SeaPhenomenon", "Incident" -> {
                    _eventsFlow.emit(EventUpdate(event))
                }
                "AnimalSighting" -> {
                    event.payload.toAnimalSighting()?.let { sighting ->
                        _animalsFlow.emit(AnimalUpdate(event, sighting))
                    }
                }
            }
        } catch (e: Exception) {
            log(LogLevel.ERROR, "Failed to process message: ${e.message}", e)
            // Don't propagate parse errors to error flow - just log them
        }
    }

    private fun shouldEmitVessel(event: Event, position: VesselPosition): Boolean {
        // Check against active subscription filters
        return subscriptions.values
            .filter { it.isActive && (it.type == SubscriptionType.VESSELS || it.type == SubscriptionType.ALL) }
            .any { handle ->
                val params = handle.params
                when (params) {
                    is SubscriptionParams.Vessels -> {
                        val passesConfidence = event.confidence >= params.minConfidence
                        val passesMMSI = params.mmsiFilter?.contains(position.mmsi) ?: true
                        val passesBBox = params.bbox?.let { bbox ->
                            position.lat in bbox.south..bbox.north &&
                            position.lon in bbox.west..bbox.east
                        } ?: true
                        passesConfidence && passesMMSI && passesBBox
                    }
                    is SubscriptionParams.All -> true
                    else -> false
                }
            }
    }

    private fun log(level: LogLevel, message: String, throwable: Throwable? = null) {
        if (level.ordinal >= config.logLevel.ordinal) {
            DefaultLogger.log(level, tag, message, throwable)
        }
    }

    companion object {
        /**
         * Create a SeaTraceClient with simple configuration.
         *
         * @param endpoint WebSocket endpoint URL
         * @param tokenProvider Optional token provider for authentication
         */
        operator fun invoke(
            endpoint: String,
            tokenProvider: (suspend () -> String)? = null
        ): SeaTraceClient {
            val config = if (tokenProvider != null) {
                SeaTraceConfig.withAuth(endpoint, tokenProvider)
            } else {
                SeaTraceConfig.simple(endpoint)
            }
            return SeaTraceClient(config)
        }

        /**
         * Create a SeaTraceClient with full configuration.
         */
        operator fun invoke(config: SeaTraceConfig): SeaTraceClient {
            return SeaTraceClient(config)
        }

        /**
         * Create a SeaTraceClient using builder pattern.
         */
        fun builder(): SeaTraceConfig.Builder = SeaTraceConfig.Builder()
    }
}
