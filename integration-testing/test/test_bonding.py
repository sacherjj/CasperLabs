from test.cl_node.casperlabsnode import HELLO_NAME
from .cl_node.wait import (
    wait_for_blocks_count_at_least
)


def test_bonding(three_node_network):
    """
    Feature file: consensus.feature
    Scenario: Bonding a validator node to an existing network.
    """
    network = three_node_network
    node0, node1, node2 = network.docker_nodes

    def wait_for_blocks_propagated(n: int) -> None:
        for node in network.docker_nodes:
            wait_for_blocks_count_at_least(node, n, n, node.timeout)

    block_hash = node0.deploy_and_propose()

    # Wait until three nodes have the genesis plus a single block node0 proposed.
    wait_for_blocks_propagated(2)

    # Add a new node, it should sync with the old ones.
    network.add_new_node_to_network()
    assert len(network.docker_nodes) == 4, "Total number of nodes should be 4."
    wait_for_blocks_propagated(2)

    node0, node1, node2, node3 = network.docker_nodes
    assert f"Attempting to add Block #1 ({block_hash}...)" in node3.logs()
