Feature: Node retains state after going down and back up

  # Initial Source: Zoom meeting as test case needed.
  Scenario: Node does down and up without requesting blocks previously seen
    Given Node1 comes up
      And Node2 comes up
      And Node1 peers Node2
      And Node2 builds 2 blocks 
      And Node2 gossips 2 blocks
      And Node1 receives 2 blocks
      And Node1 builds a DAG
     When Node1 goes down
      And Node1 comes up
      And Node2 builds 1 blocks
      And Node2 gossips 1 blocks
     Then Node1 does not ask for new block's parents