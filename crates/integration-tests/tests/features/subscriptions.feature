Feature: Real-time Subscriptions and Broadcasts
  As an Android client
  I want to subscribe to a geographic viewport
  So that I receive real-time updates for vessels and events in that area

  Scenario: Client receives broadcasted event within subscribed viewport
    Given an InMemoryBroadcaster is running
    And a client "Android-1" subscribes to viewport north 55.45 south 55.35 east 37.55 west 37.45
    When the system broadcasts a VesselPosition event at lat 55.4 lon 37.5
    Then the client "Android-1" should receive 1 event in the real-time stream
    And the received event should have mmsi 123456789
