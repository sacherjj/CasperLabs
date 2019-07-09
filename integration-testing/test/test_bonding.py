import re
from .cl_node.wait import (
    wait_for_blocks_count_at_least
)
from .cl_node.casperlabsnode import BONDING_CONTRACT, UNBONDING_CONTRACT
from .cl_node.casperlabs_network import OneNodeNetwork


def wait_for_blocks_propagated(network: OneNodeNetwork, n: int) -> None:
    for node in network.docker_nodes:
        wait_for_blocks_count_at_least(node, n, n, node.timeout)


def test_bonding(one_node_network):
    """
    Feature file: consensus.feature
    Scenario: Bonding a validator node to an existing network.
    """
    network = one_node_network
    network.add_new_node_to_network()
    wait_for_blocks_propagated(network, 1)
    assert len(network.docker_nodes) == 2, "Total number of nodes should be 2."
    node0, node1 = network.docker_nodes
    block_hash = node1.deploy_and_propose(session_contract=BONDING_CONTRACT, payment_contract=BONDING_CONTRACT)
    assert block_hash is not None
    block1 = node1.client.show_block(block_hash)
    public_key = node1.from_address
    pattern = re.compile(f'bonds\\s*{{\\s*validator_public_key: "{public_key}"\\s*stake: 1\\s*}}', re.MULTILINE)
    assert re.search(pattern, block1) is not None


def test_unbonding(one_node_network):
    """
    Feature file: consensus.feature
    Scenario: unbonding a bonded validator node from an existing network.
    """
    network = one_node_network
    network.add_new_node_to_network()
    wait_for_blocks_propagated(network, 1)
    assert len(network.docker_nodes) == 2, "Total number of nodes should be 2."
    node0, node1 = network.docker_nodes
    block_hash1 = node1.deploy_and_propose(session_contract=BONDING_CONTRACT, payment_contract=BONDING_CONTRACT)
    assert block_hash1 is not None
    block1 = node1.client.show_block(block_hash1)
    public_key = node1.from_address
    pattern = re.compile(f'bonds\\s*{{\\s*validator_public_key: "{public_key}"\\s*stake: 1\\s*}}', re.MULTILINE)
    assert re.search(pattern, block1) is not None
    block_hash2 = node1.deploy_and_propose(session_contract=UNBONDING_CONTRACT, payment_contract=UNBONDING_CONTRACT)
    assert block_hash2 is not None
    block2 = node1.client.show_block(block_hash2)
    assert re.search(pattern, block2) is None
