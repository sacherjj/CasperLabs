## Feature Files

This directory holds tests in Gherkin format.  We may or may not find that adding BDD directly into test to be valuable, but this language will allow us to capture test intents and quickly see what tests execute.

`feature_glossary.md` will hold common setups that may be used in Gherkin steps that we will build with test wrappers.
For example, Network configurations, common deploys or proposes, etc.

As you implement a test case:
 - Add to or modify feature file to match test case (if not using BDD directly, yet)
 - If new or undocumented functionality is added that is general, add/update `feature_glossary.md` for this.
  
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
 