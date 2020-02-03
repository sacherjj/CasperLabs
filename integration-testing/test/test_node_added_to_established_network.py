from casperlabs_local_net.common import Contract
from casperlabs_local_net.wait import (
    get_new_blocks_requests_total,
    wait_for_block_hashes_propagated_to_all_nodes,
    wait_for_gossip_metrics_and_assert_blocks_gossiped,
)


def test_newly_joined_node_should_not_gossip_blocks(two_node_network):
    """
    Feature file: node_added_to_established_network.feature
    Scenario: New node should not gossip old blocks back to network
    """
    network = two_node_network

    block_hashes = [
        node.deploy_and_get_block_hash(node.genesis_account, Contract.HELLO_NAME_DEFINE)
        for node in network.docker_nodes
    ]
    wait_for_block_hashes_propagated_to_all_nodes(network.docker_nodes, block_hashes)

    # Add a new node, it should sync with the old ones.
    network.add_new_node_to_network()
    wait_for_block_hashes_propagated_to_all_nodes(network.docker_nodes, block_hashes)

    node0, node1, node2 = network.docker_nodes

    # Verify that the new node didn't do any gossiping.
    wait_for_gossip_metrics_and_assert_blocks_gossiped(node2, node2.timeout, 0)

    # Verify that the original nodes didn't get their NewBlocks method called more times then expected.
    for node in network.docker_nodes[:2]:
        # node0 tells node1 about its block then node1 will try to reflect that back; 2 blocks
        # node2 should not have called the old ones during sync.
        assert get_new_blocks_requests_total(node) <= 2
