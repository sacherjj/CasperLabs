from test.cl_node.casperlabsnode import HELLO_NAME
from test.cl_node.wait import (
    get_new_blocks_requests_total,
    wait_for_blocks_count_at_least,
    wait_for_gossip_metrics_and_assert_blocks_gossiped,
)


def test_newly_joined_node_should_not_gossip_blocks(two_node_network):
    """
    Feature file: node_added_to_established_network.feature
    Scenario: New node should not gossip old blocks back to network
    """
    network = two_node_network

    def wait_for_blocks_propagated(n):
        for node in network.docker_nodes:
            wait_for_blocks_count_at_least(node, n, n, node.timeout)

    block_hashes = [node.deploy_and_propose(session_contract=HELLO_NAME) for node in network.docker_nodes]
    wait_for_blocks_propagated(3)

    node0, node1 = network.docker_nodes
    node0_new_blocks_requests_total = get_new_blocks_requests_total(node0)
    node1_new_blocks_requests_total = get_new_blocks_requests_total(node1)

    network.add_new_node_to_network()
    wait_for_blocks_propagated(3)

    node0, node1, node2 = network.docker_nodes
    for block in block_hashes:
        assert f"Attempting to add Block {block}... to DAG" in node2.logs()

    wait_for_gossip_metrics_and_assert_blocks_gossiped(node2, node2.timeout, 0)
    assert node0_new_blocks_requests_total == get_new_blocks_requests_total(node0)
    assert node1_new_blocks_requests_total == get_new_blocks_requests_total(node1)
