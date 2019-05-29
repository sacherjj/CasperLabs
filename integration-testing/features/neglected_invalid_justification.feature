Feature: Verify that simultaneous deploy and propose does not create neglected invalid blocks.


  # Implementation test_neglected_invalid_justification.py : test_neglected_invalid_block
  Scenario: 3 Nodes doing simultaneous deploys and proposes do not have neglected invalid blocks
     Given: ThreeNodeNetwork
      When: Simultaneous node-0.deploy(session="helloname_invalid_just_1.wasm"), node-1.deploy(session="helloname_invalid_just_2.wasm"), node-2.deploy(session="helloname_invalid_just_2.wasm")
       And: Simultaneous node-0.propose(), node-1.propose(), node-2.propose()
       And: Simultaneous node-0.deploy(session="helloname_invalid_just_1.wasm"), node-1.deploy(session="helloname_invalid_just_2.wasm"), node-2.deploy(session="helloname_invalid_just_2.wasm")
       And: Simultaneous node-0.propose(), node-1.propose(), node-2.propose()
       And: Simultaneous node-0.deploy(session="helloname_invalid_just_1.wasm"), node-1.deploy(session="helloname_invalid_just_2.wasm"), node-2.deploy(session="helloname_invalid_just_2.wasm")
       And: Simultaneous node-0.propose(), node-1.propose(), node-2.propose()
       And: Simultaneous node-0.deploy(session="helloname_invalid_just_1.wasm"), node-1.deploy(session="helloname_invalid_just_2.wasm"), node-2.deploy(session="helloname_invalid_just_2.wasm")
       And: Simultaneous node-0.propose(), node-1.propose(), node-2.propose()
      Then: " for NeglectedInvalidBlock." not in node-0.logs()
       And: " for NeglectedInvalidBlock." not in node-1.logs()
       And: " for NeglectedInvalidBlock." not in node-2.logs()