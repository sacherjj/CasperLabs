Feature: Genesis Ceremony

  # Reference https://casperlabs.atlassian.net/wiki/spaces/~185732238/pages/82051546/genesis+process as genesis process is finalized.

  # Initial source old/test_genesis_ceremony.py
  Scenario: Successful Genesis Ceremony
    # TODO: What setup and tests are needed
    # Genesis ceremony has changed.

  # Initial source old/test_genesis_ceremony.py
  Scenario: Validator catch up after genesis
    # TODO: What setup and tests are needed


  # Not Implemented
  Scenario: Create an account in the genesis block and fund it with token.

  # Not Implemented
  Scenario: Create an account offline, and transfer token from the genesis account to this new, non-existent account (that was created offline).

  # Not Implemented
  Scenario: Transfer token from the account created at genesis to the account that was created by a transfer transaction.
     Given: pre-created (static) public / private key pair Account
       And: pre-compiled wasm (part of CI/CD)
      When: Token Transfer Transaction is Deployed to this Account
      Then: Account exists (can receive other transfers?)
       And: Account receives correct token amount
