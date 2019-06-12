Feature: Deploy Operation

  # Are these duplications of unit tests?

  #Not Implemented
  Scenario: Deploy with no contracts
     Given: Client exists
      When: Deploy is performed with no session contract and no payment contract
      Then: Client error occurs

  #Not Implemented
  Scenario: Deploy with no session contract
     Given: Client exists
      When: Deploy is performed with payment contract and no session contract
      Then: Client error occurs

  #Not Implemented
  Scenario: Deploy with no payment contract
     Given: Client exists
      When: Deploy is performed with session contract and no payment contract
      Then: Client error occurs

  #Not Implemented
  Scenario: Deploy with both contracts
     Given: Client exists
      When: Deploy is performed with session contract and payment contract
      Then: Deploy occurs

  # Implemented: test_signed_deploys.py : test_deploy_with_invalid_signature
  Scenario; Deploy with invalid signature
     Given: Invalid account private/public key pair
      When: Deploy is performed with all fields
      Then: Client error occurs


  # Implemented: test_signed_deploys.py : test_deploy_with_valid_signature
  Scenario: Deploy with valid signature
     Given: Single Node Network
       And: Valid account private/public key pair
      When: Deploy is performed with all fields
      Then: Deploy is successful

  # Implemented: test_nonce.py : test_deploy_without_nonce
  Scenario: Deploy without nonce
     Given: Single Node Network
      When: Deploy is performed with all fields except nonce
      Then: Client error occurs

  # Implemented: test_nonce.py : test_deploy_with_lower_nonce
  Scenario: Deploy with lower nonce
     Given: Single Node Network
       And: Nonce is 3 for account
      When: Deploy is performed with nonce of 2
      Then: Block proposed shows error because of nonce

  # Implemented: test_nonce.py : test_deploy_with_higher_nonce
  Scenario: Deploy with higher nonce
     Given: Single Node Network
       And: Nonce is 1 for account
      When: Deploy is performed with nonce of 3
      Then: Propose will fail
       And: Deploy with nonce of 2 is successful
       And: Propose of block deployed with nonce of 2 is successful
       And: Subsequent propose of block deployed with nonce of 3 is successful

  # Implemented: test_nonce.py : test_deploy_with_lower_nonce (first part of the test)
  Scenario: Deploy with correct nonce
     Given: Single Node Network
       And: Nonce is 3 for account
      When: Deploy is performed with nonce of 4
      Then: Block is proposed with successful execution
