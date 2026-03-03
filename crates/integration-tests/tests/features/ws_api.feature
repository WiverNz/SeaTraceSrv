Feature: Real-time WebSockets API
  As a Client
  I want to connect to a WebSocket endpoint
  And subscribe to H3 cells using JSON
  So that I can receive events pushed to my client

  Scenario: Connect, subscribe, and receive an event over WebSocket
    Given an Axum server is running with the Control and Realtime APIs
    And a WebSocket client connects to the server
    And the client sends a subscription message for cell 613180410522075135
    When the system receives a VesselPosition event for cell 613180410522075135
    Then the WebSocket client should receive the event with MMSI 987654321
