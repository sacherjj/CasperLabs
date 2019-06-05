Feature: Block gossiping and transport

  # test_block_propagation.py
  Scenario: Blocks 'infect' the network and nodes 'closest' to the propose see the blocks first.
    Given: Network where not all nodes are connected to each other directly
     When: Block is deployed and proposed on a node
     Then: Block reaches all nodes in the network

  # Not Implemented
  Scenario: Network partition occurs and rejoin occurs
    # TODO Workout scenario

  # Not Implemented
  Scenario: Test gossiping across geographies
     Given: Network in US
      When: Network in Asia is spun up
      Then: Network in Asia connects
      When: Deploy from US
      Then: Deploy reaches Asia
       And: Time propagation to Asia
