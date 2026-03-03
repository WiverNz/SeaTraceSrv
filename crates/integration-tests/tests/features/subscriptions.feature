Feature: Real-time Subscriptions and Broadcasts
  As an Android client
  I want to subscribe to a geographic area (H3 cells)
  So that I receive real-time updates for vessels and events in that area

  Scenario: Client receives broadcasted event for a subscribed cell
    Given an InMemoryBroadcaster is running
    And a client "Android-1" subscribes to H3 cell 613180410522075135
    When the system receives a VesselPosition event for cell 613180410522075135
    Then the client "Android-1" should receive 1 event in the real-time stream
    And the received event should have mmsi 123456789
