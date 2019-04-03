Feature: Propose generates new block

  # Implemented: test_propose.py
  Scenario: Single node propose and deploy generates new block
    Given a standalone bootstrap node0 is started
     When a deploy is called on node0 with session: session.wasm and payment: session.wasm
      And a propose is called on node0
     Then block count of 1 is detected
