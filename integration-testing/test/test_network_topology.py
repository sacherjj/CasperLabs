import logging

from casperlabs_local_net.wait import wait_for_block_contains, wait_for_added_block
from casperlabs_local_net.casperlabs_accounts import GENESIS_ACCOUNT
from casperlabs_local_net.common import Contract


def test_metrics_api_socket(two_node_network):
    for node in two_node_network.docker_nodes:
        exit_code, _ = node.get_metrics()
        assert exit_code == 0, "Could not get the metrics for node {node.name}"


def check_blocks(node, expected_string, network, context, block_hash):
    logging.info("Check all peer logs for blocks containing {}".format(expected_string))

    other_nodes = [n for n in network.nodes if n.container.name != node.container.name]

    for node in other_nodes:
        wait_for_block_contains(
            node, block_hash, expected_string, context.receive_timeout
        )


def mk_expected_string(node, random_token):
    return "<{name}:{random_token}>".format(
        name=node.container.name, random_token=random_token
    )


def test_star_network(star_network):
    # Deploy on one of the star edge nodes.
    node1 = star_network.docker_nodes[1]
    block_hash = node1.deploy_and_get_block_hash(
        GENESIS_ACCOUNT, Contract.HELLO_NAME_DEFINE
    )
    # Validate all nodes get block.
    for node in star_network.docker_nodes:
        wait_for_added_block(node, block_hash, node.timeout)
