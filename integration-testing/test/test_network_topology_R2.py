import logging

from casperlabs_local_net.wait import (
    wait_for_block_contains,
    wait_for_block_hash_propagated_to_all_nodes,
)
from casperlabs_local_net.casperlabs_accounts import GENESIS_ACCOUNT
from casperlabs_local_net.common import Contract


def test_metrics_api_socket(two_node_network):
    for node in two_node_network.docker_nodes:
        exit_code, _ = node.get_metrics()
        assert exit_code == 0, "Could not get the metrics for node {node.name}"


def test_star_network(star_network):
    # Deploy on one of the star edge nodes.
    node1 = star_network.docker_nodes[1]
    block_hash = node1.deploy_and_get_block_hash(
        GENESIS_ACCOUNT, Contract.HELLO_NAME_DEFINE
    )
    # Validate all nodes get block.
    wait_for_block_hash_propagated_to_all_nodes(star_network.docker_nodes, block_hash)
