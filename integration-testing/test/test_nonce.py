import threading
from typing import List
import pytest
from test.cl_node.client_parser import parse_show_blocks
from test.cl_node.docker_node import DockerNode
from .cl_node.wait import wait_for_blocks_count_at_least
from test.cl_node.errors import NonZeroExitCodeError

"""
Feature file: ~/CasperLabs/integration-testing/features/deploy.feature
"""

@pytest.fixture()
def node(one_node_network):
    with one_node_network as network:
        # Wait for the genesis block reaching each node.
        for node in network.docker_nodes:
            wait_for_blocks_count_at_least(node, 1, 1, node.timeout)
        yield network.docker_nodes[0]


def deploy_and_propose(node, contract, nonce):
    node.client.deploy(session_contract = contract,
                       payment_contract = contract,
                       nonce = nonce)
    node.client.propose()


@pytest.mark.parametrize("contract", ['test_helloname.wasm',])
def test_deploy_without_nonce(node, contract: str):
    """
    Feature file: deploy.feature
    Scenario: Deploy without nonce
    """
    with pytest.raises(NonZeroExitCodeError):
        deploy_and_propose(node, contract, '')


@pytest.mark.parametrize("contracts", [['test_helloname.wasm', 'test_helloworld.wasm', 'test_counterdefine.wasm']])
def test_deploy_with_lower_nonce(node, contracts: List[str]):
    """
    Feature file: deploy.feature
    Scenario: Deploy with lower nonce
    """
    
    for i, contract in enumerate(contracts, 1):
        deploy_and_propose(node, contract, i)

    n = len(contracts) + 1  # add one for genesis block
    wait_for_blocks_count_at_least(node, n, n, node.timeout)

    with pytest.raises(NonZeroExitCodeError):
        deploy_and_propose(node, contract, 2)


@pytest.mark.parametrize("contract", ['test_helloname.wasm',])
def disabled_test_deploy_with_higher_nonce(node, contract: str):
    """
      Scenario: Deploy with higher nonce
         Given: Single Node Network
           And: Nonce is 3 for account
          When: Deploy is performed with nonce of 5
          Then: TODO: Does this hang until nonce of 4 is deployed???
    """
    # TODO:
    raise Exception('Not implemented yet')

