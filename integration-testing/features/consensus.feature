Feature: Consensus

  # Implemented test_block_propagation.py : test_block_propagation
  Scenario: test_helloworld.wasm deploy and propose by all nodes and stored in all nodes blockstores
     Given: 3 Node Network
      When: Node-1 Deploys test_helloworld.wasm
       And: Node-1 Proposes Block A
       And: Node-2 Deploys test_helloworld.wasm
       And: Node-2 Proposes Block B
       And: Node-3 Deploys test_helloworld.wasm
       And: Node-3 Proposes Block C
      Then: Node-1 has Blocks A, B, C
       And: Node-2 has Blocks A, B, C
       And: Node-3 has Blocks A, B, C

  # Not Implemented
  Scenario: Each node deploys a different contract.  Each node proposes block.  All nodes have all blocks.
     Given: 3 Node Network
      When: Node-1 Deploys Contract A
       And: Node-1 Proposes Block A
       And: Node-2 Deploys Contract B
       And: Node-2 Proposes Block B
       And: Node-3 Deploys Contract C
       And: Node-3 Proposes Block C
      Then: Node-1 has Blocks A, B, C
       And: Node-2 has Blocks A, B, C
       And: Node-3 had Blocks A, B, C

  # Implemented test_call_contracts_one_another.py : test_call_contracts_one_another
  Scenario: A node deploys a single wasm file which contains 3 contracts and proposes. All nodes have will have a created single block.
    Three different contracts will be deployed and proposed from each node calling the 3 different contracts which were part of single
    wasm file.
     Given: 3 Node Network
      When: Node-1 Deploys test_combinedcontractsdefine.wasm
       And: Node-1 Proposes Block A
      Then: Node-1, Node-2, Node-3 has Block A
       And: Node-1, Node-2, Node-3 deploys and proposes test_helloworld.wasm
       And: Node-1, Node-2, Node-3 deploys and proposes test_countercall.wasm
       And: Node-1, Node-2, Node-3 deploys and proposes test_mailinglistcall.wasm
      Then: Contract at path "counter/count" has been called from Node-1, Node-2, Node-3 and its value will be asserted
       And: Contract at path "mailing/list" has been called from Node-1, Node-2, Node-3 and its value will be asserted
       And: Contract at path "helloworld" has been called from Node-1, Node-2, Node-3 and its value will be asserted

  # Not Implemented
  Scenario: Orphaned blocks
      # TODO: Convert into steps
      # Create a network of N nodes, perform a deployment of a contract when the node is very busy
      # Confirm that the deployment went through
      # Wait to hear back about which block it landed in
      # Orphan the block
      # Confirm that the contract is not in the global state
      # Check the account balance that funded the deployment and confirm that no funds were deducted for the deployment (it was not finalized)

  # Not Implemented
  Scenario: Bonding
     Given: 3 Node Network
      # TODO: Convert into steps
      # Create blocks / state on the network (create some deployments & propose blocks from a single validator)
      # Let the blocks propagate through the network
      # Spin up a new node
      # Join the network
      # Observe the node catch up on state
      # Send a bonding deployment to one of the other nodes using a client
      # Observe the block # containing the bonding request
      # Send a deploy to the newly bonded validator
      # Propose via the newly bonded validator
      # Observe the block # proposed
      # Observe that the block is validated by other nodes.
      # Observe that the deployed contract is in the state of the other nodes in the network.
