# Introduction

This directory contains Python code that is intended to be used
in the new long running tests framework to simulate an ERC20 sale.

The ERC20 token used in the simulation is called ABC.

# Goals and objectives

Following goals and objectives were guiding design of the code:

- provide a Python interface to CasperLabs smart contracts that allows
  to call them as if they were ordinary Python object's methods,
- assume test network is running in auto-propose mode, client must not call
  propose,
- a separate process is used to poll node for new blocks and check status of deploys.

# Smart contract interface

Smart contract interface consists of following classes:
- Agent, representing an account on behalf of which the smart contract will be called.
- Node, representing a CasperLabs node.
- BoundAgent, representing an agent and a node.
- SmartContract, base class of classes representing concrete smart contracts such as ERC20.
- ERC20, represents the ERC20 smart contract. It is suppposed to be instantiated in order
to call method `deploy`. Other methods should be called indiretly via an instance of
DeployedERC20.
- DeployedERC20, represents already deployed ERC20.

# Running

The code was tested with docker node in hack/docker run in auto-propose mode.
`restart_nodes.sh` can be used to start the docker nodes for testing.

`make` will initialize the simulation and then run a series of transfers of tokens between test accounts. 

Initialization consists of:
- deployment of ERC20 smart contract with an initial amount of ABC tokens from the faucet account,
- transfer of initial amount of ERC20 tokens to test accounts,
- funding of test accounts with some CLX, so they can pay for calling the ERC20 methods.

Simulation is a series of transfers between random accounts (agents) of random amount of ABC,
deployed on random node in the test network.
