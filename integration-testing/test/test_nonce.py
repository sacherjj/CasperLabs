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


@pytest.mark.parametrize("contracts", [['test_helloname.wasm', 'test_helloworld.wasm', 'test_counterdefine.wasm']])
def test_deploy_with_higher_nonce(node, contracts: List[str]):
    """
    Feature file: deploy.feature

    Scenario: Deploy with higher nonce
    """
    deploy_and_propose(node, contracts[0], 1)

    # There should be the genesis block and the one we just deployed annd proposed
    wait_for_blocks_count_at_least(node, 2, 2, node.timeout)

    node.client.deploy(session_contract = contracts[2],
                       payment_contract = contracts[2],
                       nonce = 3)
    with pytest.raises(NonZeroExitCodeError):
        node.client.propose()

    h2 = deploy_and_propose(node, contracts[1], 2)

    # The deploy with nonce 3 can be proposed now
    node.client.propose()  

    blocks = parse_show_blocks(node.client.show_blocks(100))
    deploy_counts = [b.deploy_count for b in blocks]

    assert sum(deploy_counts) == len(contracts)

