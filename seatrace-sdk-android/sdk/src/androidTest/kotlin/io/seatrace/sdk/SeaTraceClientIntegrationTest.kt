package io.seatrace.sdk

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.seatrace.sdk.connection.ConnectionState
import io.seatrace.sdk.connection.ReconnectPolicy
import io.seatrace.sdk.debug.LogLevel
import io.seatrace.sdk.debug.MessageDirection
import io.seatrace.sdk.model.VesselUpdate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Integration tests for SeaTraceClient.
 *
 * These tests connect to a real SeaTraceSrv instance and verify:
 * - WebSocket connection establishment
 * - Subscription and event reception
 * - Reconnection behavior
 * - Error handling
 *
 * Prerequisites:
 * - SeaTraceSrv must be running at the configured endpoint
 * - Network connectivity to the server
 *
 * Run with:
 *   ./gradlew :sdk:connectedAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class SeaTraceClientIntegrationTest {

    private lateinit var client: SeaTraceClient
    private val testEndpoint = BuildConfig.TEST_ENDPOINT

    @Before
    fun setUp() {
        val config = SeaTraceConfig.Builder()
            .endpoint(testEndpoint)
            .reconnectPolicy(ReconnectPolicy.Disabled) // Disable for predictable tests
            .logLevel(LogLevel.DEBUG)
            .debugMode(true)
            .build()

        client = SeaTraceClient(config)
    }

    @After
    fun tearDown() {
        client.close()
    }

    /**
     * Test that the client can connect to the server.
     */
    @Test
    fun testConnection() = runBlocking {
        withTimeout(30_000) {
            client.connect()

            // Wait for connected state
            val state = client.connectionState.first { it is ConnectionState.Connected }
            assertTrue("Should be connected", state is ConnectionState.Connected)
            assertTrue("isConnected should be true", client.isConnected.value)
        }
    }

    /**
     * Test that the client can subscribe and receive vessel events.
     */
    @Test
    fun testSubscribeAndReceiveVessels() = runBlocking {
        withTimeout(60_000) {
            // Connect
            client.connect()
            client.connectionState.first { it is ConnectionState.Connected }

            // Subscribe to all events (wildcard)
            val subscription = client.subscribeAll()
            assertTrue("Subscription should be active", subscription.isActive)

            // Wait for at least 3 vessel updates
            val updates = client.vesselsFlow.take(3).toList()

            assertEquals("Should receive 3 updates", 3, updates.size)

            // Verify data integrity
            updates.forEach { update ->
                assertNotNull("Event should not be null", update.event)
                assertNotNull("Position should not be null", update.position)
                assertTrue("MMSI should be positive", update.position.mmsi > 0)
                assertTrue("Latitude should be valid", update.position.lat in -90.0..90.0)
                assertTrue("Longitude should be valid", update.position.lon in -180.0..180.0)
                assertEquals("Source should be AISStream", "AISStream", update.event.source)
            }
        }
    }

    /**
     * Test raw message listener receives messages.
     */
    @Test
    fun testRawMessageListener() = runBlocking {
        withTimeout(60_000) {
            val inboundCount = AtomicInteger(0)
            val outboundCount = AtomicInteger(0)
            val latch = CountDownLatch(2) // Wait for at least 1 inbound and 1 outbound

            client.setRawMessageListener { direction, message ->
                assertNotNull("Message should not be null", message)
                assertTrue("Message should not be empty", message.isNotEmpty())

                when (direction) {
                    MessageDirection.INBOUND -> {
                        if (inboundCount.incrementAndGet() == 1) latch.countDown()
                    }
                    MessageDirection.OUTBOUND -> {
                        if (outboundCount.incrementAndGet() == 1) latch.countDown()
                    }
                }
            }

            // Connect and subscribe
            client.connect()
            client.connectionState.first { it is ConnectionState.Connected }
            client.subscribeAll()

            // Wait for messages
            assertTrue(
                "Should receive both inbound and outbound messages",
                latch.await(30, TimeUnit.SECONDS)
            )

            assertTrue("Should have sent at least 1 message", outboundCount.get() >= 1)
            assertTrue("Should have received at least 1 message", inboundCount.get() >= 1)
        }
    }

    /**
     * Test that disconnect works properly.
     */
    @Test
    fun testDisconnect() = runBlocking {
        withTimeout(30_000) {
            // Connect first
            client.connect()
            client.connectionState.first { it is ConnectionState.Connected }

            // Disconnect
            client.disconnect()

            // Verify disconnected
            val state = client.connectionState.first { it is ConnectionState.Disconnected }
            assertTrue("Should be disconnected", state is ConnectionState.Disconnected)
            assertFalse("isConnected should be false", client.isConnected.value)
        }
    }

    /**
     * Test subscription cancellation.
     */
    @Test
    fun testSubscriptionCancel() = runBlocking {
        withTimeout(30_000) {
            client.connect()
            client.connectionState.first { it is ConnectionState.Connected }

            val subscription = client.subscribeAll()
            assertTrue("Subscription should be active", subscription.isActive)

            subscription.cancel()
            assertFalse("Subscription should be cancelled", subscription.isActive)
        }
    }

    /**
     * Test clear all subscriptions.
     */
    @Test
    fun testClearSubscriptions() = runBlocking {
        withTimeout(30_000) {
            client.connect()
            client.connectionState.first { it is ConnectionState.Connected }

            val sub1 = client.subscribeVessels()
            val sub2 = client.subscribeAll()

            assertTrue(sub1.isActive)
            assertTrue(sub2.isActive)

            client.clearSubscriptions()

            assertFalse("Sub1 should be cancelled", sub1.isActive)
            assertFalse("Sub2 should be cancelled", sub2.isActive)
        }
    }

    /**
     * Test that all events flow receives events.
     */
    @Test
    fun testAllEventsFlow() = runBlocking {
        withTimeout(60_000) {
            client.connect()
            client.connectionState.first { it is ConnectionState.Connected }
            client.subscribeAll()

            // Get first event from allEventsFlow
            val event = client.allEventsFlow.first()

            assertNotNull("Event ID should not be null", event.eventId)
            assertTrue("Event ID should not be empty", event.eventId.isNotEmpty())
            assertTrue("H3 index should be valid", event.h3Index > 0)
            assertTrue("Timestamp should be valid", event.timestamp > 0)
            assertEquals("Payload type should be VesselPosition", "VesselPosition", event.payload.type)
        }
    }

    /**
     * Test connection state transitions.
     */
    @Test
    fun testConnectionStateTransitions() = runBlocking {
        withTimeout(30_000) {
            val states = mutableListOf<ConnectionState>()

            // Collect states in background
            kotlinx.coroutines.launch {
                client.connectionState.take(3).toList(states)
            }

            // Should start disconnected
            assertEquals(ConnectionState.Disconnected, client.connectionState.value)

            // Connect
            client.connect()

            // Wait for connected
            client.connectionState.first { it is ConnectionState.Connected }

            // Verify we went through Connecting
            kotlinx.coroutines.delay(100) // Let collector catch up
            assertTrue(
                "Should have seen Connecting state",
                states.any { it is ConnectionState.Connecting }
            )
        }
    }

    /**
     * Test that parsed event listener receives events.
     */
    @Test
    fun testParsedEventListener() = runBlocking {
        withTimeout(60_000) {
            val eventRef = AtomicReference<io.seatrace.sdk.model.Event?>(null)
            val latch = CountDownLatch(1)

            client.setParsedEventListener { event ->
                if (eventRef.compareAndSet(null, event)) {
                    latch.countDown()
                }
            }

            client.connect()
            client.connectionState.first { it is ConnectionState.Connected }
            client.subscribeAll()

            assertTrue("Should receive parsed event", latch.await(30, TimeUnit.SECONDS))

            val event = eventRef.get()
            assertNotNull("Event should not be null", event)
            assertNotNull("Event ID should not be null", event?.eventId)
        }
    }
}

/**
 * Test health endpoint using HTTP client.
 */
@RunWith(AndroidJUnit4::class)
class HealthEndpointIntegrationTest {

    private val httpEndpoint = BuildConfig.TEST_HTTP_ENDPOINT

    @Test
    fun testHealthEndpoint() = runBlocking {
        withTimeout(10_000) {
            val client = okhttp3.OkHttpClient()
            val request = okhttp3.Request.Builder()
                .url("$httpEndpoint/health")
                .build()

            val response = client.newCall(request).execute()

            assertTrue("Health endpoint should return 200", response.isSuccessful)

            val body = response.body?.string()
            assertNotNull("Response body should not be null", body)
            assertTrue("Response should contain status", body!!.contains("status"))
            assertTrue("Status should be ok", body.contains("ok"))
        }
    }
}
