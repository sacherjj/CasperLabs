Feature: Execution cost is charged and collected

  # Not Implemented
  Scenario: Cost of execution is deducted from the account that pays for session contract
     Given: Single Node Network exists
       And: Account exists with balance of 10
      When: Contracts executes with Account for payment
      Then: Execution Cost was removed from Account
      # Execution Cost from ProcessedDeploy data structure, may need API)

  # Not Implemented
  Scenario: Cost of execution is added to the Proof of Stake contract
    # Note: PoS contract is what holds funds for payment of validators and has account for accumulating funds
     Given: Single Node Network exists
       And: Account exists with balance of 10
      When: Contract executes with Account for payment
      Then: Execution Cost was added to Proof of Stake contract

  # Not Implemented
  Scenario: Failed payment contract execution due to lack of payment funds, returns error and consumes funds
     Given:

