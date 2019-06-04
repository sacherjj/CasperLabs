Feature: Propose generates new block

  # Implemented: test_propose.py
  Scenario: Single node deploy and propose generates new block
    Given a standalone bootstrap node0 is started
     When a deploy is called on node0 with session: session.wasm and payment: session.wasm
      And a propose is called on node0
     Then block count of 1 is detected


  # Implemented: test_multiple_propose.py
  Scenario: Single node deploy and multiple propose generates an Exception
    Given a standalone bootstrap node0 is started
     When a deploy is called on node0 with session: session.wasm and payment: session.wasm
      And a propose is called on node0
      And a propose is called on node0 again
     Then An Exception occurs
     Then block count of 1 is detected