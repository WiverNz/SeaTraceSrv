Feature: Real-time WebSockets API
  As a Client
  I want to connect to a WebSocket endpoint
  And subscribe to a geographic viewport using JSON
  So that I can receive events pushed to my client

  Scenario: Connect, subscribe, and receive an event over WebSocket
    Given an Axum server is running with the Control and Realtime APIs
    And a WebSocket client connects to the server
    And the client sends a viewport subscription north 56.45 south 56.35 east 38.55 west 38.45
    When the system receives a VesselPosition event at lat 56.4 lon 38.5
    Then the WebSocket client should receive the event with MMSI 987654321
