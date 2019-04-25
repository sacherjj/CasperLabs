Feature: Accounts for the CasperLabs Networks

  # Not Implemented
  Scenario: Accounts should be created as part of a transaction if doesn't exist
     Given: Existing Account has balance of 100
      When: Contract executes with transfer of 10 to New Account
      Then: New Account is created
       And: New Account has balance of 10
       And: Existing Account has balance of 90
