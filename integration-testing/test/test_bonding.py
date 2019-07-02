from .cl_node.wait import (
    wait_for_blocks_count_at_least
)


def test_bonding(one_node_network):
    """
    Feature file: consensus.feature
    Scenario: Bonding a validator node to an existing network.
    """

    def wait_for_blocks_propagated(n: int) -> None:
        for node in network.docker_nodes:
            wait_for_blocks_count_at_least(node, n, n, node.timeout)

    network = one_node_network
    node0 = network.docker_nodes[0]
    network.add_new_node_to_network()
    wait_for_blocks_propagated(1)
    node0, node1 = network.docker_nodes
    # Send the bonding deployment contract as session and payment
    # contract parameters.
    block_hash = node1.deploy_and_propose()

    assert len(network.docker_nodes) == 2, "Total number of nodes should be 2."


