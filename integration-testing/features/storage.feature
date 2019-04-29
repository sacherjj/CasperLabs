Feature: Storage

   Testing node storage and retrieval of data.

  # Initial Source: old/test_storage.py
  Scenario: Data is stored and served by node
     Given Node0 is up
       And deploy is performed which stores data
      Then deploy to read data will match data

  # Figure 5 of Whitepaper
  Scenario: Simple writes of variables over multiple blocks
    
  # Figure 6 of Whitepaper
  Scenario: DAG writes of variables over multiple blocks


  Scenario: Stop a node in network, and restart it. Assert that it downloads only latest block not the whole DAG.
     Given: 2 Node Network
      When: Node-1 Deploys hello_world.wasm
       And: Node-1 Proposes Block A
       And: Node-2 Deploys hello_world.wasm
       And: Node-2 Proposes Block B
       And: Node-1 stopped
       And: Node-1 is started
       And: Node-2 Deploys hello_world.wasm
       And: Node-2 Proposes Block C
       Then: Node-1 downloads only latest Block C from Node-2 because it already had A,B.