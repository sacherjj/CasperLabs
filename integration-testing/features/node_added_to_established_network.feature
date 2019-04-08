Feature: Node added to established network

  Scenario: New node should not gossip old blocks back to network
    Given Existing network with old blocks
     When New node joins network
     Then New node should not gossip old blocks received
