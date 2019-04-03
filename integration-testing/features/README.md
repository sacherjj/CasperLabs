## Feature Files

This directory holds tests in Gherkin format.  We may or may not find that adding BDD directly into test to be valuable, but this language will allow us to capture test intents and quickly see what tests execute.
  
pytest-bdd allows painless integration with tests, using decorators to identify parts of scenarios to which methods belong.

Basic Gherkin syntax:

```
Feature: Top of File has Feature, describing the common feature in this file.

  Optional Additional Description of the Feature Here

  Scenario: Description of Scenario to be Tested
     Given Precondition or setup
       And Optional additional setup steps
      When Action that occurs
       And Optional additional action
      Then Assertion that should be made
       And Optional additional assertion
```

Note: Multiple steps of When and Then can create a multiple step scenario.  