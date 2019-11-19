Feature: Clarity

  # Implemented test_clarity.py : test_clarity_running
  Scenario: Clarity is running
     Given: Single Node Network With Clarity
       And: A running seleniumHQ container
      When: Visiting http://CLARITY-HOST-NAME:8080
      Then: The content of page contains "<title>CasperLabs Clarity</title>"

  # Implemented test_clarity.py : test_create_account_key
  Scenario: User can create/delete account key and request tokens
     Given: Single Node Network With Clarity
       And: A running seleniumHQ container
      When: Visiting http://CLARITY-HOST-NAME:8080/#/accounts
       And: Log into the Clarity
       And: Click `Create Account Key`, and input a account name
      Then: A new account with no no token has been created
      When: Using the created account to request tokens
       And: The faucet request succeed
      Then: In Accounts Key page, we can see the balance of the account is 10,000,000
      When: When click the delete button of the account
      Then: The account is deleted
