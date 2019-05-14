Feature: Account Recovery

  # Not Implemented
  Scenario: Recover tokens from account for which private keys are lost and cannot be accessed.
     Given: Old Account exists with 10 tokens
       And: Third Account exists with 20 tokens
      When: Old Account recovery is executed
      Then: New Account contains 10 tokens
       And: Old Account contains 0 tokens
      When: New Account transfers 4 tokens to Third Account
      Then: Third Account balance is 24 tokens
       And: New Account balance is 6 tokens
