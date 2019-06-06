from test.cl_node.casperlabsnode import HELLO_NAME
from test.cl_node.wait import (
    get_new_blocks_requests_total,
    wait_for_gossip_metrics_and_assert_blocks_gossiped,
    wait_for_blocks_count_at_least
)


def test_newly_joined_node_should_not_gossip_blocks(node_join_existing_network):
    """
    Feature file: node_added_to_established_network.feature
    Scenario: New node should not gossip old blocks back to network
    """
    block_hashes = []
    node0, node1 = node_join_existing_network.docker_nodes
    for node in node_join_existing_network.docker_nodes:
        block_hashes.append(node.deploy_and_propose(session_contract=HELLO_NAME,
                                                    private_key="validator-0-private.pem",
                                                    public_key="validator-0-public.pem"))
    for node in node_join_existing_network.docker_nodes:
        wait_for_blocks_count_at_least(node, 3, 3, node.timeout)
    node0_new_blocks_requests_total = get_new_blocks_requests_total(node0)
    node1_new_blocks_requests_total = get_new_blocks_requests_total(node1)

    node_join_existing_network.add_node_to_existing_network()
    node0, node1, node2 = node_join_existing_network.docker_nodes
    for block in block_hashes:
        assert f"Attempting to add Block {block}... to DAG" in node2.logs()
    wait_for_gossip_metrics_and_assert_blocks_gossiped(node2, node2.timeout, 0)
    assert node0_new_blocks_requests_total == get_new_blocks_requests_total(node0)
    assert node1_new_blocks_requests_total == get_new_blocks_requests_total(node1)
