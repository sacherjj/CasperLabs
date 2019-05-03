Feature: Star network topology operation

   Deploy works correctly for a Star topology.  (Nodes are not all interconnected.)

   # Note: This should be redefined to use star network from feature_glossary.md as we implement it for Given step.

   #
   #       Node2
   #         |
   #       Node0 -- Node1
   #         |
   #       Node3
   #
   # test_network_topology.py defined star_network_topology but DOES NOT TEST IT.
   # Not Implemented
   Scenario: Star with Node0 as hub and 3 Nodes attached will all correctly propose and deploy
      Given Node0 comes up
        And Node1 comes up
        And Node1 peers with Node0
        And Node2 comes up
        And Node2 peers with Node0
        And Node3 comes up
        And Node3 peers with Node0
       When Node0 deploys block A
        And Node1 deploys block B
        And Node2 deploys block C
        And Node3 deploys block D
       Then Node0 receives blocks A, B, C, D
        And Node1 receives blocks A, B, C, D
        And Node2 receives blocks A, B, C, D
        And Node3 receives blocks A, B, C, D
  