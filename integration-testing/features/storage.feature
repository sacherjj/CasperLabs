Feature: Storage

   Testing node storage and retrieval of data.

  # Initial Source: old/test_storage.py
  Scenario: Data is stored and served by node
     Given: Node0 is up
       And: deploy is performed which stores data
      Then: deploy to read data will match data

  # Figure 5 of Whitepaper
  Scenario: Simple writes of variables over multiple blocks
    
  # Figure 6 of Whitepaper
  Scenario: DAG writes of variables over multiple blocks


  Scenario: Stop a node in network, and restart it. Assert that it downloads only latest block not the whole DAG.
     Given: 2 Node Network
      When: Node-1 Deploys test_helloworld.wasm
       And: Node-1 Proposes Block A
       And: Node-2 Deploys test_helloworld.wasm
       And: Node-2 Proposes Block B
       And: Node-1 stopped
       And: Node-1 is started
       And: Node-2 Deploys test_helloworld.wasm
       And: Node-2 Proposes Block C
       Then: Node-1 downloads only latest Block C from Node-2 because it already had A,B.

  # Implementation test_persistent_dag_store.py : test_storage_after_multiple_node_deploy_propose_and_shutdown
  Scenario: Stop nodes and restart with correct dag and blockstore
     Given: 2 Node Network
      When: Node-0 Deploys contract
       And: Node-0 Proposes
       And: Node-1 Deploys contract
       And: Node-1 Proposes
       And: Node-0 Waits For 3 Blocks
       And: Node-1 Waits For 3 Blocks
       And: DAG-0 Saved from Node-0
       And: DAG-1 Saved from Node-1
       And: Blocks-0 Saved from Node-0
       And: Blocks-1 Saved from Node-1
       And: Node-0 Stopped
       And: Node-1 Stopped
       And: Node-0 Started
       And: Node-1 Started
      Then: Node-0 DAG equals DAG-0
       And: Node-1 DAG equals DAG-1
       And: Node-0 Blocks equals Blocks-0
       And: Node-1 Blocks equals BLocks-1
