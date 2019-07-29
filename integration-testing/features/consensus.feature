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
  Scenario: Call contracts deployed a node from another node.
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

  # Implemented test_bonding.py : test_bonding
  Scenario: Bonding a validator node to an existing single node network
    Given: Single  Node Network
     And: A bonded Validator.
     When: New node joins.
     And: Deploys bonding request.
     And: That bonding request is valid.
     Then: New node becomes bonded.
     And: Starting from new block new node can be found in bonded validators set.

  # Implemented test_bonding.py : test_invalid_bonding
  Scenario: Bonding a validator node to an existing single node network
    Given: Single  Node Network
     And: A bonded Validator.
     When: New node joins.
     And: Deploys bonding request with bonding amount 1000 times of the total of the current staked amount .
     And: That bonding request is invalid.
     Then: New node is not bonded.
     And: Starting from new block new node cannot be found in bonded validators set.

  # Implemented test_bonding.py : test_unbonding
  Scenario: Unbonding a bonded validator node from an existing network.
    Given: Single  Node Network
     And: A bonded Validator.
     When: New node joins.
     And: Deploys bonding request.
     And: That bonding request is valid.
     Then: New node becomes bonded.
     And: Starting from new block new node can be found in bonded validators set.
     And: Deploys unbonding request.
     Then: Node becomes unbonded from network.
     And: Starting from new block new node cannot be found in bonded validators set.


  # Implemented test_bonding.py : test_invalid_unbonding
  Scenario: Unbonding a bonded validator node from an existing network.
    Given: Single  Node Network
     And: A bonded Validator.
     When: New node joins.
     And: Deploys bonding request.
     And: That bonding request is valid.
     Then: New node becomes bonded.
     And: Starting from new block new node can be found in bonded validators set.
     And: Deploys unbonding request with amount greater than 90% of the total current staked amount.
     Then: Node still is bonded in network.
     And: Starting from new block new node can be found in bonded validators set.
