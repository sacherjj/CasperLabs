
Feature: Node added to established network
  # Implemented test_node_added_to_established_network.py : test_newly_joined_node_should_not_gossip_blocks
  Scenario: New node should not gossip old blocks back to network
    Given Existing network with old blocks
     When New node joins network
     Then New node should not gossip old blocks received
