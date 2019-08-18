Feature: Test Key Management Operations

  # test_key_management.py::test_deploy_threshold_cannot_exceed_key_management_threshold
  Scenario: Deploy Key Threshold cannot exceed Key Management Threshold
     Given: Payment Network with Account and Test Keys
      When: Set Key Threshold using Key Mgmt Key with Key Management Weight of 10 and Deploy Weight of 11
      Then: Deploy is error with "Exit code: 200"

  # test_key_management.py::test_key_cannot_deploy_with_weight_below_threshold
  Scenario: Cannot Deploy with weight below Deploy Threshold
     Given: Payment Network with Account and Test Keys
      When: Set Key Threshold using Key Mgmt Key with Key Management Weight of 20 and Deploy Weight of 11
      Then: Exception is raised

  # test_key_management.py::test_key_can_deploy_with_weight_at_and_above_threshold
  Scenario: Deploy is Successful with weight at and above threshold
     Given: Payment Network with Account and Test Keys
      When: Set Key Threshold using Key Mgmt Key with Key Management Weight of 20 and Deploy Weight of 9
      Then: Hello Name Deploy is Successful with Deploy Key
      When: Set Key Threshold using Key Mgmt Key with Key Management Weight of 20 and Deploy Weight of 10
      Then: Hello Name Deploy is Successful with Deploy Key

  # test_key_management.py::test_key_cannot_manage_with_weight_below_threshold
  Scenario: Cannot Manage with Key Management Weight below Threshold
     Given: Payment Network with Account and Test Keys
      When: Set Key Threshold using Key Mgmt Key with Key Management Weight of 21 and Deploy Weight of 10
      Then: Deploy is not Error
      When: Set Key Threshold with Key Management Weight of 20 and Deploy Weight of 10
      Then: Deploy is error with "Exit code: 100"
      When: Remove Associated Key using Key Mgmt Key with Deploy Key
      Then: Deploy is error with "Exit code: 1"
      When: Add Associated Key using Key Mgmt Key with Identity Ket and Weight of 10
      Then: Deploy is error with "Exit code: 100"
      When: Update Associated Key using Key Mgmt Key with Deploy Key and Weight of 11
      Then: Deploy is error with "Exit code: 100"
      When: Set Key Threshold using High Weight Key with Key Management Weight of 20 and Deploy Weight of 10
      Then: Deploy is not Error

  # test_key_management.py::test_key_can_manage_at_and_above_threshold
  Scenario: Management is possible with weight at or above threshold
     Given: Payment Network with Account and Test Keys
      When: Set Key Threshold using Key Mgmt Key with Key Management Weight of 19 and Deploy Weight of 10
      Then: Deploy is not Error
      When: Add Associated Key using Key Mgmt Key with Identity Key and Weight of 1
      Then: Deploy is not Error
      When: Update Associated Key using Key Mgmt Key with Identity Key Weight of 10
      Then: Deploy is not Error
       And: Hello Name Deploy using Identity Key is not Error
      When: Remove Associated Key using Key Mgmt Key with Identity Key
      Then: Deploy is not Error
      When: Set Key Threshold using Key Mgmt Key with Key Management Weight of 20 and Deploy Weight of 10
      Then: Deploy is not Error
      When: Add Associated Key using Key Mgmt Key with Identity Key and Weight of 1
      Then: Deploy is not Error
      When: Update Associated Key using Key Mgmt Key with Identity Key Weight of 10
      Then: Deploy is not Error
       And: Hello Name Deploy using Identity Key is not Error
      When: Remove Associated Key using Key Mgmt Key with Identity Key
      Then: Deploy is not Error

  # test_key_management.py::test_removed_key_cannot_be_used_for_deploy
  Scenario: Removed key cannot be used for deploy
     Given: Payment Network with Account and Test Keys
      When: Hello Name Deploy using Identity Key
      Then: Exception is raised
