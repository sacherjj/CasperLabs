Feature: Clarity

  # Implemented test_clarity.py : test_clarity_running
  Scenario: clarity is running
     Given: Single Node Network With Clarity
      When: Visiting http://localhost:8080
      Then: The content of page contains "<title>CasperLabs Clarity</title>"
