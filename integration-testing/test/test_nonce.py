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


def deploy(node, contract, nonce):
    node.client.deploy(session_contract = contract,
                       payment_contract = contract,
                       nonce = nonce,
                       private_key = "validator-0-private.pem",
                       public_key = "validator-0-public.pem")

@pytest.mark.parametrize("contract", ['test_helloname.wasm',])
def disabled_test_deploy_without_nonce(node, contract: str):
    """
    Feature file: deploy.feature
    Scenario: Deploy without nonce
    """
    with pytest.raises(NonZeroExitCodeError):
        deploy(node, contract, None)
        node.client.propose()
        wait_for_blocks_count_at_least(node, 2, 2, node.timeout)


@pytest.mark.parametrize("contract", ['test_helloname.wasm',])
def disabled_test_deploy_with_lower_nonce(node, contract: str):
    """
    Feature file: deploy.feature
    Scenario: Deploy with lower nonce
    """

    for i in range(5):
        deploy(node, contract, i)
        node.client.propose()
        wait_for_blocks_count_at_least(node, i+1, i+1, node.timeout)

    with pytest.raises(NonZeroExitCodeError):
        deploy(node, contract, 3)
        node.client.propose()



@pytest.mark.parametrize("contract", ['test_helloname.wasm',])
def disabled_test_deploy_with_higher_nonce(node, contract: str):
    """
      Scenario: Deploy with higher nonce
         Given: Single Node Network
           And: Nonce is 4 for account
          When: Deploy is performed with nonce of 5
          Then: TODO: Does this hang until nonce of 4 is deployed???
    """
    # TODO:
    raise Exception('Not implemented yet')

