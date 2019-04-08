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

  